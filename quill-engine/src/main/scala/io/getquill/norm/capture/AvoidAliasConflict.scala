package io.getquill.norm.capture

import io.getquill.ast.{ Entity, Filter, FlatJoin, FlatMap, GroupBy, Ident, Join, Map, Query, SortBy, StatefulTransformer, _ }
import io.getquill.ast.Implicits._
import io.getquill.norm.{ BetaReduction, Normalize }
import io.getquill.util.Interpolator
import io.getquill.util.Messages.TraceType

import scala.collection.immutable.Set

/**
 * This phase of normalization ensures that symbol names in every layer of the Ast are unique.
 * For instances, take something like this:
 * {{{
 *   FlatMap(A, a, Filter(Entity(E), a, a.id == a.fk)
 * }}}
 * This would typically correspond to SQL that looks roughly like this:
 * {{{
 *   SELECT ... from A a, (SELECT ... from E a WHERE a.id = a.fk)
 * }}}
 *
 * The problem is that the inner Ident 'a' is indistinguishable from the outer Ident 'a'. For this reason,
 * the inner Ident a needs to be changed into a1 which would turn the expression into this:
 * {{{
 *   FlatMap(A, a, Filter(Entity(E), a1, a.id == a1.fk)
 * }}}
 * Which would then correspond to the following SQL:
 * {{{
 *   SELECT ... from A a, (SELECT ... from E a1 WHERE a.id = a1.fk)
 * }}}
 *
 * Over time, this class has been incorporated with various functionality in order to multiple places
 * throughout the Quill codebase. The chief function however remains the same. To make sure
 * that aliases do not conflict.
 *
 * One important side-function of this transformation is to transform temporary variables (e.g. as created by
 * the `AttachToEntity` phase) into permanant ones of the form x[0-9]+. Since `AvoidAliasConflict` typically
 * runs not on the entire Ast but the sub-parts of it used by normalizations, making temporary aliases
 * permanent cannot be done in these sub-parts because the 'state' of this transformation is not fully know
 * i.e. because aliases of outer clauses may not be present. For this reason, this transformation is specifically
 * called once from the top-level inside `SqlNormalize` at the very end of the transformation pipeline.
 */
private[getquill] case class AvoidAliasConflict(state: Set[IdentName], detemp: Boolean)
  extends StatefulTransformer[Set[IdentName]] {

  val interp = new Interpolator(TraceType.AvoidAliasConflict, 3)
  import interp._

  object Unaliased {

    private def isUnaliased(q: Ast): Boolean =
      q match {
        case Nested(q: Query)         => isUnaliased(q)
        case Take(q: Query, _)        => isUnaliased(q)
        case Drop(q: Query, _)        => isUnaliased(q)
        case Aggregation(_, q: Query) => isUnaliased(q)
        case Distinct(q: Query)       => isUnaliased(q)
        case _: Entity | _: Infix     => true
        case _                        => false
      }

    def unapply(q: Ast): Option[Ast] =
      q match {
        case q if (isUnaliased(q)) => Some(q)
        case _                     => None
      }
  }

  // Cannot realize direct super-cluase of a join because of how ExpandJoin does $a$b.
  // This is tested in JoinComplexSpec which verifies that ExpandJoin behaves correctly.
  object CanRealias {
    def unapply(q: Ast): Boolean =
      q match {
        case _: Join => false
        case _       => true
      }
  }

  private def recurseAndApply[T <: Query](elem: T)(ext: T => (Ast, Ident, Ast))(f: (Ast, Ident, Ast) => T): (T, StatefulTransformer[Set[IdentName]]) =
    trace"Uncapture RecurseAndApply $elem " andReturn {
      val (newElem, newTrans) = super.apply(elem)
      val ((query, alias, body), state) =
        (ext(newElem.asInstanceOf[T]), newTrans.state)

      val fresh = freshIdent(alias)
      val pr =
        trace"RecurseAndApply Replace: $alias -> $fresh: " andReturn
          BetaReduction(body, alias -> fresh)

      (f(query, fresh, pr), AvoidAliasConflict(state + fresh.idName, detemp))
    }

  override def apply(qq: Query): (Query, StatefulTransformer[Set[IdentName]]) =
    trace"Uncapture $qq " andReturn
      qq match {

        case FlatMap(Unaliased(q), x, p) =>
          apply(x, p)(FlatMap(q, _, _))

        case ConcatMap(Unaliased(q), x, p) =>
          apply(x, p)(ConcatMap(q, _, _))

        case Map(Unaliased(q), x, p) =>
          apply(x, p)(Map(q, _, _))

        case Filter(Unaliased(q), x, p) =>
          apply(x, p)(Filter(q, _, _))

        case GroupBy(Unaliased(q), x, p) =>
          apply(x, p)(GroupBy(q, _, _))

        case DistinctOn(Unaliased(q), x, p) =>
          apply(x, p)(DistinctOn(q, _, _))

        case m @ FlatMap(CanRealias(), _, _) =>
          recurseAndApply(m)(m => (m.query, m.alias, m.body))(FlatMap(_, _, _))

        case m @ ConcatMap(CanRealias(), _, _) =>
          recurseAndApply(m)(m => (m.query, m.alias, m.body))(ConcatMap(_, _, _))

        case m @ Map(CanRealias(), _, _) =>
          recurseAndApply(m)(m => (m.query, m.alias, m.body))(Map(_, _, _))

        case m @ Filter(CanRealias(), _, _) =>
          recurseAndApply(m)(m => (m.query, m.alias, m.body))(Filter(_, _, _))

        case m @ GroupBy(CanRealias(), _, _) =>
          recurseAndApply(m)(m => (m.query, m.alias, m.body))(GroupBy(_, _, _))

        case m @ DistinctOn(CanRealias(), _, _) =>
          recurseAndApply(m)(m => (m.query, m.alias, m.body))(DistinctOn(_, _, _))

        case SortBy(Unaliased(q), x, p, o) =>
          trace"Unaliased $qq uncapturing $x" andReturn
          apply(x, p)(SortBy(q, _, _, o))

        case Join(t, a, b, iA, iB, o) =>
          trace"Uncapturing Join $qq" andReturn {
            val (ar, art) = apply(a)
            val (br, brt) = art.apply(b)
            val freshA = freshIdent(iA, brt.state)
            val freshB = freshIdent(iB, brt.state + freshA.idName)
            val or =
              trace"Uncapturing Join: Replace $iA -> $freshA, $iB -> $freshB" andReturn
                BetaReduction(o, iA -> freshA, iB -> freshB)
            val (orr, orrt) =
              trace"Uncapturing Join: Recurse with state: ${brt.state} + $freshA + $freshB" andReturn
                AvoidAliasConflict(brt.state + freshA.idName + freshB.idName, detemp)(or)

            (Join(t, ar, br, freshA, freshB, orr), orrt)
          }

        case FlatJoin(t, a, iA, o) =>
          trace"Uncapturing FlatJoin $qq" andReturn {
            val (ar, art) = apply(a)
            val freshA = freshIdent(iA)
            val or =
              trace"Uncapturing FlatJoin: Reducing $iA -> $freshA" andReturn
                BetaReduction(o, iA -> freshA)
            val (orr, orrt) =
              trace"Uncapturing FlatJoin: Recurse with state: ${art.state} + $freshA" andReturn
                AvoidAliasConflict(art.state + freshA.idName, detemp)(or)

            (FlatJoin(t, ar, freshA, orr), orrt)
          }

        case _: Entity | _: FlatMap | _: ConcatMap | _: Map | _: Filter | _: SortBy | _: GroupBy |
        _: Aggregation | _: Take | _: Drop | _: Union | _: UnionAll | _: Distinct | _: Nested =>
          super.apply(qq)
      }

  private def apply(x: Ident, p: Ast)(f: (Ident, Ast) => Query): (Query, StatefulTransformer[Set[IdentName]]) =
    trace"Uncapture Apply ($x, $p)" andReturn {
      val fresh = freshIdent(x)
      val pr =
        trace"Uncapture Apply: $x -> $fresh" andReturn
          BetaReduction(p, x -> fresh)
      val (prr, t) =
        trace"Uncapture Apply Recurse" andReturn
          AvoidAliasConflict(state + fresh.idName, detemp)(pr)

      (f(fresh, prr), t)
    }

  /**
   * If the ident is temporary (e.g. given by [tmp_{UUID}] give it an actual variable
   * and the make sure it does not conflict with any other variables of outer
   * clauses in the AST (freshIdent does that part).
   */
  private def freshIdent(x: Ident, state: Set[IdentName] = state): Ident = {
    x match {
      case TemporaryIdent(tid) if (detemp) =>
        dedupeIdent(Ident("x", tid.quat), state)
      case _ =>
        dedupeIdent(x, state)
    }
  }

  private def dedupeIdent(x: Ident, state: Set[IdentName] = state): Ident = {
    def loop(x: Ident, n: Int): Ident = {
      val fresh = Ident(s"${x.name}$n", x.quat)
      if (!state.contains(fresh.idName))
        fresh
      else
        loop(x, n + 1)
    }
    if (!state.contains(x.idName))
      x
    else
      loop(x, 1)
  }

  /**
   * Sometimes we need to change the variables in a function because they might conflict with some variable
   * further up in the macro. Right now, this only happens when you do something like this:
   * <code>
   * val q = quote { (v: Foo) => query[Foo].insert(v) }
   * run(q(lift(v)))
   * </code>
   * Since 'v' is used by actionMeta in order to map keys to values for insertion, using it as a function argument
   * messes up the output SQL like so:
   * <code>
   *   INSERT INTO MyTestEntity (s,i,l,o) VALUES (s,i,l,o) instead of (?,?,?,?)
   * </code>
   * Therefore, we need to have a method to remove such conflicting variables from Function ASTs
   */
  private def applyFunction(f: Function): Function = {
    val (newBody, _, newParams) =
      f.params.foldLeft((f.body, state, List[Ident]())) {
        case ((body, state, newParams), param) => {
          val fresh = freshIdent(param)
          val pr = BetaReduction(body, param -> fresh)
          val (prr, t) = AvoidAliasConflict(state + fresh.idName, false)(pr)
          (prr, t.state, newParams :+ fresh)
        }
      }
    Function(newParams, newBody)
  }

  private def applyForeach(f: Foreach): Foreach = {
    val fresh = freshIdent(f.alias)
    val pr = BetaReduction(f.body, f.alias -> fresh)
    val (prr, _) = AvoidAliasConflict(state + fresh.idName, false)(pr)
    Foreach(f.query, fresh, prr)
  }
}

private[getquill] object AvoidAliasConflict {

  def Ast(q: Ast, detemp: Boolean = false): Ast =
    new AvoidAliasConflict(Set[IdentName](), detemp)(q) match {
      case (q, _) => q
    }

  def apply(q: Query, detemp: Boolean = false): Query =
    AvoidAliasConflict(Set[IdentName](), detemp)(q) match {
      case (q, _) => q
    }

  /**
   * Make sure query parameters do not collide with paramters of a AST function. Do this
   * by walkning through the function's subtree and transforming and queries encountered.
   */
  def sanitizeVariables(f: Function, dangerousVariables: Set[IdentName]): Function = {
    AvoidAliasConflict(dangerousVariables, false).applyFunction(f)
  }

  /** Same is `sanitizeVariables` but for Foreach **/
  def sanitizeVariables(f: Foreach, dangerousVariables: Set[IdentName]): Foreach = {
    AvoidAliasConflict(dangerousVariables, false).applyForeach(f)
  }

  def sanitizeQuery(q: Query, dangerousVariables: Set[IdentName]): Query = {
    AvoidAliasConflict(dangerousVariables, false).apply(q) match {
      // Propagate aliasing changes to the rest of the query
      case (q, _) => Normalize(q)
    }
  }
}
