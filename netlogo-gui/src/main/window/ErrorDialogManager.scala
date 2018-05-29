// (C) Uri Wilensky. https://github.com/NetLogo/NetLogo

package org.nlogo.window

import java.awt.Component
import java.awt.event.{ ActionEvent, ActionListener, ItemEvent, ItemListener }
import java.nio.file.Path

import javax.swing.{ JButton, JCheckBox, JComponent }

import org.nlogo.core.I18N
import org.nlogo.api.{ LogoException, Version }
import org.nlogo.nvm.{ Context, Instruction }
import org.nlogo.swing.{ BrowserLauncher, MessageDialog }
import org.nlogo.util.Utils
import org.nlogo.util.SysInfo

import scala.annotation.tailrec

case class ErrorInfo(var throwable: Throwable, var context: Option[Context] = None, var instruction: Option[Instruction] = None) {
  def ordinaryError: Boolean = throwable.isInstanceOf[LogoException]

  def hasKnownCause: Boolean = knownAncestorCause(throwable)

  def isOutOfMemory: Boolean = knownAncestorCause(throwable)

  def hasContext: Boolean = context.nonEmpty

  def errorMessage: Option[String] =
    context.flatMap(c => instruction.map(i => (c, i))).map {
      case (ctx, ins) => ctx.buildRuntimeErrorMessage(ins, throwable)
    } orElse (if (ordinaryError) Some(throwable.getMessage) else None)

  @tailrec
  private def knownAncestorCause(t: Throwable): Boolean =
    t.isInstanceOf[OutOfMemoryError] || (t.getCause != null && knownAncestorCause(t.getCause))
}

case class DebuggingInfo(var className: String, var threadName: String, var modelName: String, var eventTrace: String, var javaStackTrace: String) {
  def debugInfo =
    s"""|${Version.version}
        |main: $className
        |thread: $threadName
        |${SysInfo.getVMInfoString}
        |${SysInfo.getOSInfoString}
        |${SysInfo.getScalaVersionString}
        |${SysInfo.getJOGLInfoString}
        |${SysInfo.getGLInfoString}
        |model: $modelName""".stripMargin

  def detailedInformation: String = s"""|$javaStackTrace
                                        |$debugInfo
                                        |
                                        |$eventTrace""".stripMargin
}

class ErrorDialogManager(owner: Component) {
  private val debuggingInfo = DebuggingInfo("", "", "", "", "")
  private val errorInfo = ErrorInfo(null)
  private val unknownDialog = new UnknownErrorDialog(owner)
  private val logoDialog    = new LogoExceptionDialog(owner)
  private val memoryDialog  = new OutOfMemoryDialog(owner)

  debuggingInfo.className = owner.getClass.getName

  def setModelName(name: String): Unit = {
    debuggingInfo.modelName = name
  }

  def alreadyVisible: Boolean = {
    Seq(unknownDialog, logoDialog, memoryDialog).exists(_.isVisible)
  }

  def show(context: Context, instruction: Instruction, thread: Thread, throwable: Throwable): Unit = {
      debuggingInfo.threadName     = thread.getName
      debuggingInfo.eventTrace     = Event.recentEventTrace()
      debuggingInfo.javaStackTrace = Utils.getStackTrace(throwable)
      errorInfo.throwable   = throwable
      errorInfo.context     = Option(context)
      errorInfo.instruction = Option(instruction)
      throwable match {
        case l: LogoException             => logoDialog.doShow(errorInfo, debuggingInfo)
        case _ if errorInfo.isOutOfMemory => memoryDialog.doShow()
        case _                            => unknownDialog.doShow("Internal Error", errorInfo, debuggingInfo)
      }
  }

  // This was added to work around https://bugs.openjdk.java.net/browse/JDK-8198809,
  // which appears only in Java 8u162 and should be resolved in 8u172.
  // In general, this method should be used as a safety valve for non-fatal exceptions which
  // are Java's fault (this bug matches that description to a tee, but there are
  // many other bugs of this sort). - RG 3/2/18
  def safeToIgnore(t: Throwable): Boolean = {
    t match {
      case j: java.awt.IllegalComponentStateException =>
        val classAndMethodNames = Seq(
          "java.awt.Component"                                         -> "getLocationOnScreen_NoTreeLock",
          "java.awt.Component"                                         -> "getLocationOnScreen",
          "javax.swing.text.JTextComponent$InputMethodRequestsHandler" -> "getTextLocation",
          "sun.awt.im.InputMethodContext"                              -> "getTextLocation",
          "sun.awt.windows.WInputMethod$1"                             -> "run")
        val stackTraceClassAndMethodNames =
          j.getStackTrace.take(5).map(ste => ste.getClassName -> ste.getMethodName).toSeq
        classAndMethodNames == stackTraceClassAndMethodNames
      case _ => false
    }
  }
}

trait CopyButton {
  def copy(): Unit

  lazy val copyButton: JButton = new JButton(I18N.gui.get("menu.edit.copy"))
  copyButton.addActionListener(new ActionListener() {
    def actionPerformed(e: ActionEvent): Unit = { copy() }
  })
}

object ErrorDialog {
  val PleaseReportText = I18N.gui.get("error.dialog.pleaseReport")
}

import ErrorDialog._

trait ErrorDialog {
  protected var textWithDetails: String    = ""
  protected var textWithoutDetails: String = ""

  protected lazy val checkbox = {
    val b = new JCheckBox(I18N.gui.get("error.dialog.showInternals"))
    b.addItemListener(new ItemListener() {
      def itemStateChanged(e: ItemEvent): Unit = {
        showJavaDetails(b.isSelected)
      }
    })
    b
  }

  protected def showJavaDetails(showDetails: Boolean): Unit = {
    var lines = 1
    var lineBegin = 0
    var longestLine = 0
    val text =
        if (showDetails) textWithDetails else textWithoutDetails

    var i = 0
    while (i < text.length) {
      text.charAt(i) match {
        case '\n' | '\r' =>
          lines += 1
          longestLine = longestLine max (i - lineBegin)
          lineBegin = i
        case _ =>
      }
      i += 1
    }

    longestLine += 2 // pad
    lines = lines.max(5).min(15)
    longestLine = longestLine.min(70)

    showText(text, lines, longestLine)
  }

  protected def showText(text: String, rows: Int, columns: Int): Unit
}

class UnknownErrorDialog(owner: Component) extends MessageDialog(owner, I18N.gui.get("common.buttons.dismiss")) with ErrorDialog with CopyButton {
  private lazy val suppressButton = new JButton(I18N.gui.get("error.dialog.suppress"))

  private var dialogTitle: String = ""
  private var errorHeader: String = ""

  private var suppressed = false

  def doShow(showTitle: String, errorInfo: ErrorInfo, debuggingInfo: DebuggingInfo): Unit = {
    if (suppressed)
      return
    dialogTitle = showTitle
    // we don't need bug reports on known issues like OutOfMemoryError - ST 4/29/10
    errorHeader =
      if (! (errorInfo.ordinaryError || errorInfo.hasKnownCause)) PleaseReportText
      else ""
    // only has context if the exception occured inside running Logo code (and not, for example, in the GUI)
    suppressButton.setVisible(! (errorInfo.ordinaryError || errorInfo.hasContext))
    buildTexts(errorInfo, debuggingInfo)
    checkbox.setVisible(errorInfo.ordinaryError)
    showJavaDetails(! errorInfo.ordinaryError || checkbox.isSelected)
  }

  override def copy(): Unit = {
    val beginIndex = textArea.getText.indexOf(errorHeader) + errorHeader.length
    textArea.select(beginIndex, textArea.getText.length)
    textArea.copy()
    textArea.setCaretPosition(0)
  }

  override def makeButtons(): Seq[JComponent] = {
    suppressButton.addActionListener(new ActionListener() {
      def actionPerformed(e: ActionEvent): Unit = {
        suppressed = true
        setVisible(false)
      }
    })
    super.makeButtons() ++ Seq(copyButton, checkbox, suppressButton)
  }

  private def buildTexts(errorInfo: ErrorInfo, debuggingInfo: DebuggingInfo): Unit = {
    val detailedInformation =
      s"$errorHeader\n${debuggingInfo.detailedInformation}"
    textWithoutDetails = errorInfo.errorMessage.getOrElse("")
    textWithDetails = errorInfo.errorMessage
      .map(_ + "\n\n" + detailedInformation)
      .getOrElse(detailedInformation)
  }

  override protected def showText(text: String, rows: Int, columns: Int): Unit =
    doShow(dialogTitle, text, rows, columns)
}

class LogoExceptionDialog(owner: Component) extends MessageDialog(owner, I18N.gui.get("common.buttons.dismiss")) with ErrorDialog with CopyButton {
  private val dialogTitle: String = I18N.gui.get("common.messages.error.runtimeError")

  def doShow(errorInfo: ErrorInfo, debuggingInfo: DebuggingInfo): Unit = {
    buildTexts(errorInfo, debuggingInfo)
    showJavaDetails(checkbox.isSelected)
  }

  override def copy(): Unit = {
    textArea.select(0, textArea.getText.length)
    textArea.copy()
    textArea.setCaretPosition(0)
  }

  override def makeButtons(): Seq[JComponent] =
    super.makeButtons() ++ Seq(copyButton, checkbox)

  private def buildTexts(errorInfo: ErrorInfo, debuggingInfo: DebuggingInfo): Unit = {
    textWithoutDetails = errorInfo.errorMessage.getOrElse("")
    textWithDetails    = errorInfo.errorMessage
      .map(_ + "\n\n" + debuggingInfo.detailedInformation)
      .getOrElse(debuggingInfo.detailedInformation)
  }

  override protected def showText(text: String, rows: Int, columns: Int): Unit =
    doShow(dialogTitle, text, rows, columns)
}

class OutOfMemoryDialog(owner: Component) extends MessageDialog(owner, I18N.gui.get("common.buttons.dismiss")) with ErrorDialog {
  private val dialogTitle: String = I18N.gui.get("error.dialog.outOfMemory.title")
  private val ErrorText = I18N.gui.get("error.dialog.outOfMemory")

  override def makeButtons(): Seq[JComponent] = {
    val openFAQ = new JButton(I18N.gui.get("error.dialog.openFAQ"))
    val baseFaqUrl: Path = BrowserLauncher.docPath("faq.html")
    openFAQ.addActionListener(new ActionListener() {
      def actionPerformed(e: ActionEvent): Unit = {
        BrowserLauncher.openPath(owner, baseFaqUrl, "howbig")
      }
    })
    super.makeButtons() :+ openFAQ
  }

  def doShow(): Unit = {
    textWithDetails = ErrorText
    showJavaDetails(true)
  }

  override protected def showText(text: String, rows: Int, columns: Int): Unit =
    doShow(dialogTitle, text, rows, columns)
}
