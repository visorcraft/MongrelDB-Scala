package dev.visorcraft.mongreldb

import java.nio.charset.StandardCharsets
import java.util.Locale

/** Minimal JSON codec used internally by the client.
  *
  * It encodes and decodes `Map[String, Any]`, `List[Any]`, `Number`, `Boolean`,
  * `String`, and `null` - the exact shape the daemon's JSON API uses - without
  * pulling in a third-party dependency. This is intentionally narrow: it is not a
  * general-purpose JSON library.
  */
private[mongreldb] object Json:

  /** Encodes a value to a UTF-8 JSON byte array. */
  def toBytes(value: Any): Array[Byte] =
    val sb = new StringBuilder
    write(sb, value)
    sb.toString.getBytes(StandardCharsets.UTF_8)

  /** Parses UTF-8 JSON bytes into Map/List/primitive. */
  def parse(body: Array[Byte]): Any =
    val s = new String(body, StandardCharsets.UTF_8)
    val p = Parser(s)
    p.skipWs()
    val v = p.readValue()
    p.skipWs()
    if p.pos < p.src.length then
      throw QueryException(s"mongreldb: trailing JSON content at ${p.pos}")
    v

  /** A short, safe preview of a body for error messages. */
  def preview(body: Array[Byte]): String =
    val s = new String(body, StandardCharsets.UTF_8)
    if s.length > 120 then s.substring(0, 120) + "..." else s

  // ── Encoder ─────────────────────────────────────────────────────────────

  private def write(sb: StringBuilder, v: Any): Unit = v match
    case null                 => sb.append("null")
    case m: Map[?, ?] @unchecked =>
      sb.append('{')
      var first = true
      m.foreach { (k, value) =>
        if !first then sb.append(',')
        first = false
        writeString(sb, String.valueOf(k))
        sb.append(':')
        write(sb, value)
      }
      sb.append('}')
    case l: Iterable[?] @unchecked =>
      sb.append('[')
      var first = true
      l.foreach { o =>
        if !first then sb.append(',')
        first = false
        write(sb, o)
      }
      sb.append(']')
    case s: String            => writeString(sb, s)
    case b: Boolean           => sb.append(b.toString)
    case d: java.lang.Double  => emitDouble(sb, d)
    case f: java.lang.Float   => emitDouble(sb, f.toDouble)
    case n: Number            => sb.append(n.toString)
    case c: Character         => writeString(sb, c.toString)
    case other                => writeString(sb, String.valueOf(other))

  private def emitDouble(sb: StringBuilder, d: Double): Unit =
    // NaN and Infinity have no valid JSON representation; emit null.
    if java.lang.Double.isNaN(d) || java.lang.Double.isInfinite(d) then sb.append("null")
    else sb.append(d.toString)

  private def writeString(sb: StringBuilder, s: String): Unit =
    sb.append('"')
    var i = 0
    while i < s.length do
      s.charAt(i) match
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case c =>
          if c < 0x20 then sb.append(String.format(Locale.ROOT, "\\u%04x", java.lang.Integer.valueOf(c.toInt)))
          else sb.append(c)
      i += 1
    sb.append('"')

  // ── Parser (recursive descent) ──────────────────────────────────────────

  private final class Parser(val src: String):
    var pos = 0

    def skipWs(): Unit =
      while pos < src.length do
        src.charAt(pos) match
          case ' ' | '\t' | '\n' | '\r' => pos += 1
          case _ => return

    def readValue(): Any =
      skipWs()
      if pos >= src.length then throw QueryException("mongreldb: unexpected end of JSON")
      src.charAt(pos) match
        case '{' => readObject()
        case '[' => readArray()
        case '"' => readString()
        case 't' | 'f' => readBool()
        case 'n' => readNull()
        case _ => readNumber()

    def readObject(): Map[String, Any] =
      expect('{')
      val b = Map.newBuilder[String, Any]
      skipWs()
      if peek() == '}' then { pos += 1; return b.result() }
      while true do
        skipWs()
        val key = readString()
        skipWs()
        expect(':')
        b += (key -> readValue())
        skipWs()
        next() match
          case ',' => // continue
          case '}' => return b.result()
          case c => throw QueryException(s"mongreldb: expected ',' or '}' at ${pos - 1}")

    def readArray(): List[Any] =
      expect('[')
      val b = List.newBuilder[Any]
      skipWs()
      if peek() == ']' then { pos += 1; return b.result() }
      while true do
        b += readValue()
        skipWs()
        next() match
          case ',' => // continue
          case ']' => return b.result()
          case c => throw QueryException(s"mongreldb: expected ',' or ']' at ${pos - 1}")

    def readString(): String =
      expect('"')
      val sb = new StringBuilder
      while pos < src.length do
        src.charAt(pos) match
          case c =>
            pos += 1
            c match
              case '"' => return sb.toString
              case '\\' =>
                if pos >= src.length then throw QueryException("mongreldb: unterminated escape")
                val e = src.charAt(pos); pos += 1
                e match
                  case '"'  => sb.append('"')
                  case '\\' => sb.append('\\')
                  case '/'  => sb.append('/')
                  case 'n'  => sb.append('\n')
                  case 'r'  => sb.append('\r')
                  case 't'  => sb.append('\t')
                  case 'b'  => sb.append('\b')
                  case 'f'  => sb.append('\f')
                  case 'u' =>
                    if pos + 4 > src.length then throw QueryException("mongreldb: bad \\u escape")
                    val hex = src.substring(pos, pos + 4); pos += 4
                    try sb.append(hex.toInt(16).toChar)
                    catch case _: NumberFormatException =>
                      throw QueryException(s"mongreldb: bad \\u escape: $hex")
                  case other => throw QueryException(s"mongreldb: bad escape '\\$other'")
              case other => sb.append(other)
      throw QueryException("mongreldb: unterminated string")

    def readBool(): Boolean =
      if src.startsWith("true", pos) then { pos += 4; true }
      else if src.startsWith("false", pos) then { pos += 5; false }
      else throw QueryException(s"mongreldb: invalid literal at $pos")

    def readNull(): Any =
      if src.startsWith("null", pos) then { pos += 4; null }
      else throw QueryException(s"mongreldb: invalid literal at $pos")

    def readNumber(): Any =
      val start = pos
      if peek() == '-' then pos += 1
      var continue = true
      while pos < src.length && continue do
        val c = src.charAt(pos)
        if (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-' then
          pos += 1
        else
          continue = false
      val num = src.substring(start, pos)
      if num.isEmpty then throw QueryException(s"mongreldb: invalid number at $start")
      if num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0 then
        try num.toLong
        catch case _: NumberFormatException => java.math.BigInteger(num)
      else num.toDouble

    private def peek(): Char =
      if pos >= src.length then '\u0000' else src.charAt(pos)

    private def next(): Char =
      if pos >= src.length then throw QueryException("mongreldb: unexpected end of JSON")
      val c = src.charAt(pos); pos += 1; c

    private def expect(c: Char): Unit =
      val actual = next()
      if actual != c then
        throw QueryException(s"mongreldb: expected '$c' but got '$actual' at ${pos - 1}")
end Json
