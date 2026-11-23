package com.welivethisway.bots.api

import android.graphics.Path

/**
 * A small SVG path-data parser (M/L/H/V/C/S/Q/T/Z, absolute and relative).
 * The avatar shapes this app draws — the Rakazo shipped shapes, the logo
 * mark, the organic blobs and the MausBot mascot bodies — only use
 * M, C, Q and Z, but the rest of the common commands are here too so a
 * new shape does not silently break. Arcs (A) are not used by any of
 * them and are rejected.
 */
object SvgPath {

    fun parse(data: String): Path {
        val path = Path()
        val tokens = tokenize(data)
        var i = 0
        var cmd = ' '
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        var lastCubicX2 = 0f
        var lastCubicY2 = 0f
        var lastQuadX1 = 0f
        var lastQuadY1 = 0f
        var lastCmd = ' '

        fun nextNum(): Float {
            if (i >= tokens.size) throw IllegalArgumentException("Truncated path")
            return tokens[i++].toFloat()
        }

        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]
                i++
                if (cmd == 'Z' || cmd == 'z') {
                    path.close()
                    cx = startX
                    cy = startY
                    lastCmd = cmd
                    continue
                }
            }
            val rel = cmd.isLowerCase()
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = nextNum()
                    val y = nextNum()
                    cx = if (rel) cx + x else x
                    cy = if (rel) cy + y else y
                    path.moveTo(cx, cy)
                    startX = cx
                    startY = cy
                    // Further coordinate pairs after M are implicit lineto.
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> {
                    val x = nextNum()
                    val y = nextNum()
                    cx = if (rel) cx + x else x
                    cy = if (rel) cy + y else y
                    path.lineTo(cx, cy)
                }
                'H' -> {
                    val x = nextNum()
                    cx = if (rel) cx + x else x
                    path.lineTo(cx, cy)
                }
                'V' -> {
                    val y = nextNum()
                    cy = if (rel) cy + y else y
                    path.lineTo(cx, cy)
                }
                'C' -> {
                    val x1 = nextNum(); val y1 = nextNum()
                    val x2 = nextNum(); val y2 = nextNum()
                    val x = nextNum(); val y = nextNum()
                    val ax1 = if (rel) cx + x1 else x1
                    val ay1 = if (rel) cy + y1 else y1
                    val ax2 = if (rel) cx + x2 else x2
                    val ay2 = if (rel) cy + y2 else y2
                    val ax = if (rel) cx + x else x
                    val ay = if (rel) cy + y else y
                    path.cubicTo(ax1, ay1, ax2, ay2, ax, ay)
                    lastCubicX2 = ax2
                    lastCubicY2 = ay2
                    cx = ax
                    cy = ay
                }
                'S' -> {
                    val x2 = nextNum(); val y2 = nextNum()
                    val x = nextNum(); val y = nextNum()
                    val ax1: Float
                    val ay1: Float
                    if (lastCmd.uppercaseChar() == 'C' || lastCmd.uppercaseChar() == 'S') {
                        ax1 = 2 * cx - lastCubicX2
                        ay1 = 2 * cy - lastCubicY2
                    } else {
                        ax1 = cx
                        ay1 = cy
                    }
                    val ax2 = if (rel) cx + x2 else x2
                    val ay2 = if (rel) cy + y2 else y2
                    val ax = if (rel) cx + x else x
                    val ay = if (rel) cy + y else y
                    path.cubicTo(ax1, ay1, ax2, ay2, ax, ay)
                    lastCubicX2 = ax2
                    lastCubicY2 = ay2
                    cx = ax
                    cy = ay
                }
                'Q' -> {
                    val x1 = nextNum(); val y1 = nextNum()
                    val x = nextNum(); val y = nextNum()
                    val ax1 = if (rel) cx + x1 else x1
                    val ay1 = if (rel) cy + y1 else y1
                    val ax = if (rel) cx + x else x
                    val ay = if (rel) cy + y else y
                    path.quadTo(ax1, ay1, ax, ay)
                    lastQuadX1 = ax1
                    lastQuadY1 = ay1
                    cx = ax
                    cy = ay
                }
                'T' -> {
                    val x = nextNum(); val y = nextNum()
                    val ax1: Float
                    val ay1: Float
                    if (lastCmd.uppercaseChar() == 'Q' || lastCmd.uppercaseChar() == 'T') {
                        ax1 = 2 * cx - lastQuadX1
                        ay1 = 2 * cy - lastQuadY1
                    } else {
                        ax1 = cx
                        ay1 = cy
                    }
                    val ax = if (rel) cx + x else x
                    val ay = if (rel) cy + y else y
                    path.quadTo(ax1, ay1, ax, ay)
                    lastQuadX1 = ax1
                    lastQuadY1 = ay1
                    cx = ax
                    cy = ay
                }
                else -> throw IllegalArgumentException("Unsupported path command: $cmd")
            }
            lastCmd = cmd
        }
        return path
    }

    /** Splits path data into command letters and numbers. */
    private fun tokenize(data: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < data.length) {
            val c = data[i]
            when {
                c.isWhitespace() || c == ',' -> i++
                c.isLetter() -> {
                    out += c.toString()
                    i++
                }
                else -> {
                    val start = i
                    if (c == '-' || c == '+') i++
                    var seenDot = false
                    var seenExp = false
                    while (i < data.length) {
                        val d = data[i]
                        if (d.isDigit()) {
                            i++
                        } else if (d == '.' && !seenDot && !seenExp) {
                            seenDot = true
                            i++
                        } else if ((d == 'e' || d == 'E') && !seenExp) {
                            seenExp = true
                            i++
                            if (i < data.length && (data[i] == '-' || data[i] == '+')) i++
                        } else {
                            break
                        }
                    }
                    out += data.substring(start, i)
                }
            }
        }
        return out
    }
}
