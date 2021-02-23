package com.raquo.waypoint

import com.raquo.waypoint.fixtures.UnitSpec

class UtilsSpec extends UnitSpec {

  private val fallbackFun = (_: String) => "fallback"
  private val passAll: PartialFunction[String, String] = {
    case any => any
  }
  private val passShort: PartialFunction[String, String] = {
    case any if any.length < 5 => any
  }
  private val passPass: PartialFunction[String, String] = {
    case s if s.contains("pass") => s
  }

  private val allAndThenShort = Utils.andThenPF(passAll, passShort)
  private val shortAndThenAll = Utils.andThenPF(passShort, passAll)
  private val allAndThenPass = Utils.andThenPF(passAll, passPass)
  private val passAndThenAll = Utils.andThenPF(passPass, passAll)
  private val passAndThenShort = Utils.andThenPF(passPass, passShort)
  private val shortAndThenPass = Utils.andThenPF(passShort, passPass)

  it("andThenPF") {
    assert(allAndThenShort.applyOrElse("pass", fallbackFun) == "pass")
    assert(shortAndThenAll.applyOrElse("pass", fallbackFun) == "pass")
    assert(allAndThenShort.isDefinedAt("pass"))
    assert(shortAndThenAll.isDefinedAt("pass"))

    assert(allAndThenPass.applyOrElse("pass", fallbackFun) == "pass")
    assert(passAndThenAll.applyOrElse("pass", fallbackFun) == "pass")
    assert(allAndThenPass.isDefinedAt("pass"))
    assert(passAndThenAll.isDefinedAt("pass"))

    assert(allAndThenPass.applyOrElse("longpass", fallbackFun) == "longpass")
    assert(passAndThenAll.applyOrElse("longpass", fallbackFun) == "longpass")
    assert(allAndThenPass.isDefinedAt("longpass"))
    assert(passAndThenAll.isDefinedAt("longpass"))

    assert(allAndThenShort.applyOrElse("longpass", fallbackFun) == "fallback")
    assert(shortAndThenAll.applyOrElse("longpass", fallbackFun) == "fallback")
    assert(!allAndThenShort.isDefinedAt("longpass"))
    assert(!shortAndThenAll.isDefinedAt("longpass"))

    assert(allAndThenPass.applyOrElse("longstr", fallbackFun) == "fallback")
    assert(passAndThenAll.applyOrElse("longstr", fallbackFun) == "fallback")
    assert(!allAndThenPass.isDefinedAt("longstr"))
    assert(!passAndThenAll.isDefinedAt("longstr"))

    assert(passAndThenShort.applyOrElse("pass", fallbackFun) == "pass")
    assert(shortAndThenPass.applyOrElse("pass", fallbackFun) == "pass")
    assert(passAndThenShort.isDefinedAt("pass"))
    assert(shortAndThenPass.isDefinedAt("pass"))

    assert(passAndThenShort.applyOrElse("longpass", fallbackFun) == "fallback")
    assert(shortAndThenPass.applyOrElse("longpass", fallbackFun) == "fallback")
    assert(!passAndThenShort.isDefinedAt("longpass"))
    assert(!shortAndThenPass.isDefinedAt("longpass"))

    assert(shortAndThenPass.applyOrElse("longstr", fallbackFun) == "fallback")
    assert(passAndThenShort.applyOrElse("longstr", fallbackFun) == "fallback")
    assert(!shortAndThenPass.isDefinedAt("longstr"))
    assert(!passAndThenShort.isDefinedAt("longstr"))
  }
}
