// (C) Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.compiler

import org.scalatest.FunSuite
import org.nlogo.nvm.Procedure
import org.nlogo.api.{ DummyExtensionManager, Program }

class AssemblerTests extends FunSuite {
  def compile(keyword: String, source: String): Procedure = {
    implicit val tokenizer = Compiler.Tokenizer2D
    val program = new Program(false)
    val results = new StructureParser(
      tokenizer.tokenize(keyword + " foo " + source + "\nend"), None,
      program, java.util.Collections.emptyMap[String, Procedure],
      new DummyExtensionManager)
      .parse(false)
    assertResult(1)(results.procedures.size)
    val procedure = results.procedures.values.iterator.next()
    val tokens =
      new IdentifierParser(program, java.util.Collections.emptyMap[String, Procedure],
        results.procedures, false)
        .process(results.tokens(procedure).iterator, procedure)
    for (procdef <- new ExpressionParser(procedure).parse(tokens)) {
      procdef.accept(new ArgumentStuffer)
      new Assembler().assemble(procdef)
    }
    procedure
  }

  // these tests focus more on assembly, ignoring argument stuffing.
  // the test strings omit arguments, to make them easier to read & write.
  def test1(source: String) = compile("to", source).code.mkString(" ")
  test("assembleEmptyProcedure") { assertResult("_return")(test1("")) }
  test("assembleSimpleProcedure1") { assertResult("_clearall _return")(test1("ca")) }
  test("assembleSimpleProcedure2") { assertResult("_clearturtles _clearpatches _return")(test1("ct cp")) }
  test("assembleIfElse") {
    assertResult("_ifelse:+4 _fd _fdinternal _goto:6 _bk _fdinternal _return")(
      test1("ifelse timer = 0 [ fd 1 ] [ bk 1 ]"))
  }
  test("assembleAsk") {
    assertResult("_ask:+3 _die _done _return")(
      test1("ask turtles [ die ]"))
  }
  test("assembleWhile") {
    assertResult("_goto:3 _die _die _while:1 _return")(
      test1("while [true] [die die]"))
  }
  test("assembleReporterProcedure") {
    assertResult("_returnreport")(
      compile("to-report", "").code.mkString(" "))
  }

  // these tests are more about checking argument stuffing is working.
  // we check the full text of the procedure dump.
  def test2(source: String): String = {
    val dump = compile("to", source).dump
    val prelude = "procedure FOO:[]{OTPL}:\n"
    assert(dump.startsWith(prelude))
    dump.substring(prelude.length)
  }
  test("stuffEmpty") {
    assertResult("""|[0]_return
              |""".stripMargin.replaceAll("\r\n", "\n"))(
      test2(""))
  }
  test("stuffArithmetic") {
    assertResult("""|[0]_print
           |      _plus
           |        _constdouble:2.0
           |        _constdouble:2.0
           |[1]_return
           |""".stripMargin.replaceAll("\r\n", "\n"))(
      test2("print 2 + 2"))
  }
  test("stuffReporterBlock") {
    assertResult("""|[0]_print
           |      _maxoneof
           |        _turtles
           |        _timer
           |[1]_return
           |""".stripMargin.replaceAll("\r\n", "\n"))(
      test2("print max-one-of turtles [timer]"))
  }
}
