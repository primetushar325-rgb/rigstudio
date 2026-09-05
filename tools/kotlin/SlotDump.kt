package com.rigstudio.tools

import com.rigstudio.core.template.BarInk
import com.rigstudio.core.template.CharacterSheetTemplate
import com.rigstudio.core.template.TemplateInk
import com.rigstudio.core.template.TemplateLayoutSolver
import com.rigstudio.core.template.TextInk
import com.rigstudio.core.template.TriangleInk
import java.io.File

/**
 * Dumps the character sheet template to JSON on stdout.
 *
 * This is a **development tool**, not product code: it lives outside `core/src/main` so it never
 * ships in the APK. The Python tools in `tools/` (reference renderer, sheet checker) read the JSON
 * it produces, which keeps them honest — they describe the *real* slot table instead of a copy
 * that can silently drift.
 *
 * Run it through `tools/dump_slots.sh`.
 */
fun main(args: Array<String>) {
    val template = CharacterSheetTemplate
    val problems = template.selfCheck()
    if (problems.isNotEmpty()) {
        System.err.println("TEMPLATE SELF-CHECK FAILED:")
        problems.forEach { System.err.println("  - $it") }
        kotlin.system.exitProcess(2)
    }

    val out = StringBuilder(64 * 1024)
    out.append("{\n")
    out.append("  \"version\": ${template.VERSION},\n")
    out.append("  \"sheetWidth\": ${template.SHEET_WIDTH},\n")
    out.append("  \"sheetHeight\": ${template.SHEET_HEIGHT},\n")
    out.append("  \"safeMargin\": ${template.SAFE_MARGIN},\n")
    out.append("  \"gutter\": ${template.GUTTER},\n")
    out.append("  \"slots\": [\n")
    template.SLOTS.forEachIndexed { index, slot ->
        out.append("    {")
        out.append("\"id\": ${q(slot.id)}, ")
        out.append("\"label\": ${q(slot.label)}, ")
        out.append("\"group\": ${q(slot.group)}, ")
        out.append("\"view\": ${q(slot.view.name)}, ")
        out.append("\"kind\": ${q(slot.kind.name)}, ")
        out.append("\"x\": ${slot.rect.x}, ")
        out.append("\"y\": ${slot.rect.y}, ")
        out.append("\"w\": ${slot.rect.width}, ")
        out.append("\"h\": ${slot.rect.height}, ")
        out.append("\"pivotX\": ${slot.pivot.x}, ")
        out.append("\"pivotY\": ${slot.pivot.y}, ")
        out.append("\"boneId\": ${slot.boneId?.let { q(it) } ?: "null"}, ")
        out.append("\"expression\": ${slot.expression?.let { q(it.name) } ?: "null"}, ")
        out.append("\"mouthShape\": ${slot.mouthShape?.let { q(it.name) } ?: "null"}, ")
        out.append("\"required\": ${slot.required}")
        out.append(if (index == template.SLOTS.lastIndex) "}\n" else "},\n")
    }
    out.append("  ],\n")
    out.append("  \"notesAreas\": [\n")
    template.NOTES_AREAS.forEachIndexed { index, area ->
        out.append("    {\"x\": ${area.x}, \"y\": ${area.y}, \"w\": ${area.width}, \"h\": ${area.height}}")
        out.append(if (index == template.NOTES_AREAS.lastIndex) "\n" else ",\n")
    }
    out.append("  ]\n")
    out.append("}\n")

    val target = args.firstOrNull()
    if (target != null) {
        File(target).writeText(out.toString())
        System.err.println("Wrote ${template.SLOTS.size} slots to $target")
    } else {
        print(out)
    }

    // The solved guide-ink layout: what the Android renderer and the Python reference renderer
    // both draw. Dumping it keeps the two implementations from drifting apart.
    val layout = TemplateLayoutSolver.solve()
    val layoutJson = StringBuilder(256 * 1024)
    layoutJson.append("{\n")
    layoutJson.append("  \"version\": ${template.VERSION},\n")
    layoutJson.append("  \"sheetWidth\": ${layout.width},\n")
    layoutJson.append("  \"sheetHeight\": ${layout.height},\n")
    layoutJson.append("  \"unplacedLabels\": [${layout.unplacedLabels.joinToString(", ") { q(it) }}],\n")
    layoutJson.append("  \"ink\": [\n")
    layout.ink.forEachIndexed { index, ink ->
        layoutJson.append("    ")
        layoutJson.append(inkJson(ink))
        layoutJson.append(if (index == layout.ink.lastIndex) "\n" else ",\n")
    }
    layoutJson.append("  ]\n}\n")

    val layoutTarget = args.getOrNull(1) ?: (target?.let { File(it).parent }?.let { "$it/layout.json" })
    if (layoutTarget != null) {
        File(layoutTarget).writeText(layoutJson.toString())
        System.err.println("Wrote ${layout.ink.size} ink primitives to $layoutTarget")
    } else {
        print(layoutJson)
    }
    if (layout.unplacedLabels.isNotEmpty()) {
        System.err.println("UNPLACED LABELS: ${layout.unplacedLabels}")
        kotlin.system.exitProcess(3)
    }
}

private fun inkJson(ink: TemplateInk): String {
    val common = StringBuilder()
    common.append("\"role\": ${q(ink.role.name)}, ")
    common.append("\"x\": ${ink.bounds.x}, \"y\": ${ink.bounds.y}, ")
    common.append("\"w\": ${ink.bounds.width}, \"h\": ${ink.bounds.height}")
    return when (ink) {
        is BarInk -> "{\"type\": \"bar\", $common, \"slotId\": ${ink.slotId?.let { q(it) } ?: "null"}}"
        is TriangleInk -> "{\"type\": \"triangle\", $common, \"slotId\": ${q(ink.slotId)}, " +
            "\"points\": [${ink.points.joinToString(", ") { "[${it.x}, ${it.y}]" }}]}"
        is TextInk -> "{\"type\": \"text\", $common, \"text\": ${q(ink.text)}, " +
            "\"sizePx\": ${ink.sizePx}, \"anchorX\": ${ink.anchorX}, \"baselineY\": ${ink.baselineY}, " +
            "\"vertical\": ${ink.vertical}, \"slotId\": ${ink.slotId?.let { q(it) } ?: "null"}}"
    }
}

private fun q(value: String): String {
    val escaped = StringBuilder(value.length + 2)
    escaped.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> escaped.append("\\\"")
            '\\' -> escaped.append("\\\\")
            '\n' -> escaped.append("\\n")
            '\r' -> escaped.append("\\r")
            '\t' -> escaped.append("\\t")
            else -> if (ch.code < 0x20) escaped.append("\\u%04x".format(ch.code)) else escaped.append(ch)
        }
    }
    escaped.append('"')
    return escaped.toString()
}
