package com.raquo.waypoint

object Utils {

  // @TODO[Security] This is rather ad-hoc. Review later.
  @inline private[waypoint] def isRelative(url: String): Boolean = {
    url.startsWith("/") && !url.startsWith("//")
  }

  @inline private[waypoint] def absoluteUrlMatchesOrigin(origin: String, url: String): Boolean = {
    url.startsWith(origin + "/")
  }

  // @TODO[Scala3] The isDefinedOf method of pf1.andThen(pf2) is broken in Scala 2.12, it does not consider pf2.isDefinedOf
  //  - This is fixed in Scala 2.13, but until then we provide an implementation similar to 2.13 (with the same tests as in scala/scala)
  //  - https://github.com/scala/scala/pull/7263
  @inline private[waypoint] def andThenPF[A, B, C](pf: PartialFunction[A, B], k: PartialFunction[B, C]): PartialFunction[A, C] = {
    new PartialFunction[A, C] with Serializable {
      def isDefinedAt(x: A): Boolean = {
        val b = pf.andThen[Option[B]](Some(_)).applyOrElse(x, (_: A) => None)
        b.exists(k.isDefinedAt)
      }

      def apply(x: A): C = k(pf(x))

      override def applyOrElse[A2 <: A, C1 >: C](x: A2, default: A2 => C1): C1 = {
        val pfv = pf.andThen[Option[B]](Some(_)).applyOrElse(x, (_: A2) => None)
        pfv.map(v => k.applyOrElse(v, (_: B) => default(x))).getOrElse(default(x))
      }
    }
  }
}
