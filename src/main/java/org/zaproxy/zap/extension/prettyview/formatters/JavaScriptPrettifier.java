package org.zaproxy.zap.extension.prettyview.formatters;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class JavaScriptPrettifier
implements PrettyPrettifier {
  private static final int SOFT_WRAP_COLUMN = 180;
  private static final Set<String> LOOP_KEYWORDS = new HashSet<String>(Arrays.asList("for", "while"));
  private static final Set<String> OBJECT_KEYWORDS = new HashSet<String>(Arrays.asList("return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "yield", "await", "case", "default"));
  /** Module clauses such as {@code import {a} from "m"} keep their braces on a single line. */
  private static final Set<String> CLAUSE_KEYWORDS = new HashSet<String>(Arrays.asList("import", "export"));

  @Override
  public PrettyPrettifier.SupportedFormat getSupportedFormat() {
    return PrettyPrettifier.SupportedFormat.JAVASCRIPT;
  }

  @Override
  public String prettify(String body) {
    if (body == null || body.isEmpty()) {
      return "";
    }
    Writer writer = new Writer(body.length());
    State state = State.CODE;
    int parenDepth = 0;
    int loopParenBase = -1;
    StringBuilder lastWord = new StringBuilder();
    int length = body.length();
    for (int i = 0; i < length; ++i) {
      char c = body.charAt(i);
      char next = i + 1 < length ? body.charAt(i + 1) : (char)'\u0000';
      char previous = i > 0 ? body.charAt(i - 1) : (char)'\u0000';
      switch (state) {
        case LINE_COMMENT: {
          writer.append(c);
          if (c != '\n') break;
          state = State.CODE;
          break;
        }
        case BLOCK_COMMENT: {
          writer.append(c);
          if (previous != '*' || c != '/') break;
          state = State.CODE;
          writer.newLine();
          break;
        }
        case SINGLE_QUOTE: 
        case DOUBLE_QUOTE: {
          writer.append(c);
          if (c == '\\') {
            if (next == '\u0000') break;
            writer.append(next);
            ++i;
            break;
          }
          if (c != (state == State.SINGLE_QUOTE ? (char)'\'' : '\"') && c != '\n') break;
          state = State.CODE;
          break;
        }
        case TEMPLATE: {
          writer.append(c);
          if (c == '\\') {
            if (next == '\u0000') break;
            writer.append(next);
            ++i;
            break;
          }
          if (c != '`') break;
          state = State.CODE;
          break;
        }
        case REGEX: {
          writer.append(c);
          if (c == '\\') {
            if (next == '\u0000') break;
            writer.append(next);
            ++i;
            break;
          }
          if (c != '/' && c != '\n') break;
          state = State.CODE;
          break;
        }
        default: {
          // handleCode empties lastWord, so anything needed for a keyword decision has to be read first.
          String wordBefore = c == '(' ? lastWord.toString() : "";
          String completedWord = writer.lastWord;
          char before = writer.lastSignificant;
          char twoBefore = writer.beforeSignificant;
          state = this.handleCode(writer, state, c, next, previous, lastWord);
          if (state != State.CODE) break;
          if (c == '{') {
            int kind = braceKind(before, twoBefore, completedWord, writer.afterCaseColon);
            writer.lastSignificant = '{';
            writer.beforeSignificant = before;
            writer.afterCaseColon = false;
            writer.openBrace(kind);
            break;
          }
          if (c == '}') {
            writer.lastSignificant = '}';
            writer.beforeSignificant = before;
            writer.closeBrace();
            break;
          }
          if (c == ':' && writer.consumeCaseLabel()) {
            writer.lastSignificant = ':';
            writer.beforeSignificant = before;
            writer.endCaseLabel();
            break;
          }
          if (!Character.isWhitespace(c)) {
            writer.lastSignificant = c;
            writer.beforeSignificant = before;
            writer.afterCaseColon = false;
          }
          if (c == '(') {
            if (LOOP_KEYWORDS.contains(wordBefore)) {
              loopParenBase = parenDepth;
            }
            ++parenDepth;
            break;
          }
          if (c != ')') break;
          parenDepth = Math.max(0, parenDepth - 1);
          if (loopParenBase >= 0 && parenDepth <= loopParenBase) {
            loopParenBase = -1;
          }
        }
      }
      if (state != State.CODE) continue;
      if (c == ';') {
        if (loopParenBase >= 0 && parenDepth > loopParenBase) continue;
        writer.newLine();
      }
    }
    return writer.finish();
  }

  private State handleCode(Writer writer, State current, char c, char next, char previous, StringBuilder lastWord) {
    if (Character.isJavaIdentifierPart(c)) {
      lastWord.append(c);
      writer.append(c);
      return State.CODE;
    }
    if (c == '/' && next == '/') {
      writer.append(c).append(next);
      lastWord.setLength(0);
      return State.LINE_COMMENT;
    }
    if (c == '/' && next == '*') {
      writer.append(c).append(next);
      lastWord.setLength(0);
      return State.BLOCK_COMMENT;
    }
    if (c == '/' && JavaScriptPrettifier.startsRegex(previous, lastWord)) {
      writer.append(c);
      lastWord.setLength(0);
      return State.REGEX;
    }
    if (c == '\'') {
      writer.append(c);
      lastWord.setLength(0);
      return State.SINGLE_QUOTE;
    }
    if (c == '\"') {
      writer.append(c);
      lastWord.setLength(0);
      return State.DOUBLE_QUOTE;
    }
    if (c == '`') {
      writer.append(c);
      lastWord.setLength(0);
      return State.TEMPLATE;
    }
    String word = lastWord.toString();
    writer.lastWord = word;
    // inside an object literal or a module clause, "case:1" and "default" are property names and
    // module keywords rather than switch labels
    if (!writer.insideNonBlock() && ("case".equals(word) || "default".equals(word))) {
      writer.startCaseLabel();
    }
    lastWord.setLength(0);
    if (c == '\n') {
      writer.newLine();
      return State.CODE;
    }
    if (c != '{' && c != '}') {
      writer.append(c);
    }
    if (c == ',' && writer.lineLength() > SOFT_WRAP_COLUMN) {
      writer.newLine();
    }
    return State.CODE;
  }

  private static boolean startsRegex(char previous, StringBuilder lastWord) {
    if (lastWord.length() > 0) {
      return false;
    }
    if (previous == '\u0000') {
      return true;
    }
    return "(,=:[!&|?{};+-*%~^".indexOf(previous) >= 0;
  }

  /** Classifies a {@code {}: a block, an object literal, or a single-line module clause. */
  private static final int BLOCK = 0;
  private static final int LITERAL = 1;
  private static final int CLAUSE = 2;

  private static int braceKind(char before, char twoBefore, String completedWord, boolean afterCaseColon) {
    if (CLAUSE_KEYWORDS.contains(completedWord)) {
      return CLAUSE;
    }
    return JavaScriptPrettifier.isObjectLiteral(before, twoBefore, completedWord, afterCaseColon) ? LITERAL : BLOCK;
  }

  /**
   * 1TBS applies to the brace that opens a <em>block</em>. A brace opening an object literal belongs
   * to an expression, so it stays attached to it, keeping {@code return {x:1}} and {@code var o={a:1}}
   * intact instead of stranding the brace on a line of its own. A brace right after {@code )},
   * {@code ;}, {@code {} or a keyword like {@code else} always opens a block; an arrow function body
   * after {@code =>} does too.
   */
  private static boolean isObjectLiteral(char before, char twoBefore, String completedWord, boolean afterCaseColon) {
    if (OBJECT_KEYWORDS.contains(completedWord)) {
      return true;
    }
    switch (before) {
      case '=':
      case '(':
      case ',':
      case '[':
      case '?':
      case '&':
      case '|':
      case '+':
      case '-':
      case '*':
      case '/':
      case '%':
      case '!':
      case '~':
      case '^':
      case '<':
        return true;
      case ':':
        // a value position is an object literal, but "case X: {" opens a block
        return !afterCaseColon;
      case '>':
        // "=>" is an arrow function body, so it is a block; a bare ">" is a comparison
        return twoBefore != '=';
      default:
        return false;
    }
  }

  private static final class Writer {
    private final StringBuilder out;
    private final StringBuilder line = new StringBuilder();
    private int indent;
    /** The last identifier that ended before the current character. */
    String lastWord;
    /** The two most recent non-whitespace code characters, used to tell a block brace from a literal one. */
    char lastSignificant;
    char beforeSignificant;
    /** True right after the {@code :} that ended a case label, so a following brace is a block. */
    boolean afterCaseColon;
    private boolean pendingCase;
    private boolean inCaseBody;
    /** Indent of the switch/if the active case body lives in, so only that brace ends the body. */
    private int caseIndent;
    /** One entry per open brace: {@link #BLOCK}, {@link #LITERAL} or {@link #CLAUSE}. */
    private int[] braceKinds = new int[16];
    private int braceDepth;

    Writer(int capacity) {
      this.out = new StringBuilder(Math.max(256, capacity + capacity / 4 + 64));
    }

    Writer append(char c) {
      if (c == '\n') {
        this.newLine();
      } else {
        this.line.append(c);
      }
      return this;
    }

    void trimTrailingWhitespace() {
      while (this.line.length() > 0 && Character.isWhitespace(this.line.charAt(this.line.length() - 1))) {
        this.line.setLength(this.line.length() - 1);
      }
    }

    private void pushBrace(int kind) {
      if (this.braceDepth == this.braceKinds.length) {
        this.braceKinds = Arrays.copyOf(this.braceKinds, this.braceDepth * 2);
      }
      this.braceKinds[this.braceDepth++] = kind;
    }

    /** True inside an object literal or a module clause, where {@code case:1} is a property, not a label. */
    boolean insideNonBlock() {
      return this.braceDepth > 0 && this.braceKinds[this.braceDepth - 1] != BLOCK;
    }

    void openBrace(int kind) {
      this.pushBrace(kind);
      // a brace after "export default" means that word was not a case label
      this.pendingCase = false;
      if (kind == CLAUSE) {
        this.trimTrailingWhitespace();
        if (this.line.length() > 0) {
          this.line.append(' ');
        }
        this.line.append('{');
        return;
      }
      if (kind == LITERAL) {
        this.trimTrailingWhitespace();
        if (this.line.length() > 0 && Character.isJavaIdentifierPart(this.line.charAt(this.line.length() - 1))) {
          this.line.append(' ');
        }
        this.line.append('{');
        this.newLine();
        ++this.indent;
        return;
      }
      // 1TBS: the opening brace of a block stays on the line that introduces it, so "if (a) {"
      // keeps the brace attached and only the body moves down a level. An empty line means the brace
      // opens a statement of its own - a bare block, or the body of a case label - and stands alone.
      this.trimTrailingWhitespace();
      if (this.line.length() > 0) {
        this.line.append(' ');
      }
      this.line.append('{');
      this.newLine();
      ++this.indent;
    }

    void closeBrace() {
      int kind = this.braceDepth > 0 ? this.braceKinds[--this.braceDepth] : BLOCK;
      if (kind == CLAUSE) {
        // everything after a module clause stays on the same line, so just close it in place
        this.append('}');
        return;
      }
      if (kind == LITERAL) {
        // A literal's brace closes the value, so whatever follows it - a comma, a semicolon, the end
        // of the payload - stays on the same line.
        this.newLine();
        this.indent = Math.max(0, this.indent - 1);
        this.append('}');
        return;
      }
      this.newLine();
      // Only the brace that owns the switch ends a case body; a nested block such as the one an
      // "if" opens closes at its own level and must not dedent the case body with it.
      if (this.inCaseBody && this.indent - 1 == this.caseIndent) {
        // release the case body's own level, then the block level below
        this.indent = Math.max(0, this.indent - 1);
        this.inCaseBody = false;
      }
      this.indent = Math.max(0, this.indent - 1);
      this.append('}');
      this.newLine();
    }

    /** A {@code case} or {@code default} label starts, so step out of the previous body. */
    void startCaseLabel() {
      this.leaveCaseBody();
      this.pendingCase = true;
    }

    boolean consumeCaseLabel() {
      boolean pending = this.pendingCase;
      this.pendingCase = false;
      return pending;
    }

    /** The label's {@code :} has been written, so the statements under it get a level of their own. */
    void endCaseLabel() {
      this.caseIndent = this.indent;
      this.newLine();
      ++this.indent;
      this.inCaseBody = true;
      this.afterCaseColon = true;
    }

    private void leaveCaseBody() {
      if (!this.inCaseBody) {
        return;
      }
      this.indent = Math.max(0, this.indent - 1);
      this.inCaseBody = false;
    }

    void newLine() {
      if (this.line.length() == 0) {
        return;
      }
      this.trimTrailingWhitespace();
      if (this.line.length() == 0) {
        return;
      }
      for (int i = 0; i < this.indent; ++i) {
        this.out.append(PrettyPrettifier.INDENT);
      }
      this.out.append((CharSequence)this.line).append('\n');
      this.line.setLength(0);
    }

    int lineLength() {
      return this.line.length();
    }

    String finish() {
      this.newLine();
      return this.out.toString();
    }
  }

  private static enum State {
    CODE,
    SINGLE_QUOTE,
    DOUBLE_QUOTE,
    TEMPLATE,
    LINE_COMMENT,
    BLOCK_COMMENT,
    REGEX;

  }
}