package com.rigstudio.core.tests

import com.rigstudio.core.geom.IntRect
import com.rigstudio.core.harness.Assert
import com.rigstudio.core.harness.TestCase
import com.rigstudio.core.json.Json
import com.rigstudio.core.json.JsonParseException
import com.rigstudio.core.json.JsonValue
import com.rigstudio.core.json.arr
import com.rigstudio.core.json.bool
import com.rigstudio.core.json.boolean
import com.rigstudio.core.json.float
import com.rigstudio.core.json.get
import com.rigstudio.core.json.int
import com.rigstudio.core.json.num
import com.rigstudio.core.json.obj
import com.rigstudio.core.json.str
import com.rigstudio.core.json.string
import com.rigstudio.core.json.stringList
import com.rigstudio.core.model.CharacterProject
import com.rigstudio.core.model.Expression
import com.rigstudio.core.model.MouthShape
import com.rigstudio.core.model.ProjectCodec
import com.rigstudio.core.model.SpriteManifestEntry
import com.rigstudio.core.model.ViewKind
import com.rigstudio.core.rig.RigBuilder
import com.rigstudio.core.rig.ViewAvailability
import com.rigstudio.core.support.Fixtures

/**
 * Persistence (spec §19, §20) and the JSON layer underneath it.
 *
 * The important guarantee is the last test: a character that is saved, read back and rebuilt must
 * produce exactly the same rig — reopening a project can never change how the character animates.
 */
object ProjectTests {

    private fun manifestOf(result: com.rigstudio.core.extract.SheetProcessResult) =
        result.sprites.values.map { sprite ->
            SpriteManifestEntry(
                slotId = sprite.slotId,
                fileName = "${sprite.slotId}.png",
                width = sprite.width,
                height = sprite.height,
                pivotX = sprite.pivot.x,
                pivotY = sprite.pivot.y,
                coverage = sprite.coverage,
                sourceRect = sprite.sourceRect,
                contentRect = sprite.contentRect,
            )
        }

    private fun sampleProject(result: com.rigstudio.core.extract.SheetProcessResult) = CharacterProject(
        id = "proj_01",
        name = "Captain Test",
        createdAtEpochMillis = 1_700_000_000_000L,
        updatedAtEpochMillis = 1_700_000_500_000L,
        lastOpenedAtEpochMillis = 1_700_000_900_000L,
        sheetFileName = "sheet.png",
        sheetWidth = 2048,
        sheetHeight = 2048,
        thumbnailFileName = "thumb.png",
        sprites = manifestOf(result),
        availableViews = listOf(ViewKind.FRONT, ViewKind.SIDE_LEFT),
        mirroredSideView = true,
        availableExpressions = listOf(Expression.NEUTRAL, Expression.HAPPY),
        availableMouthShapes = listOf(MouthShape.CLOSED, MouthShape.A, MouthShape.SMILE),
        notes = listOf("Side View artwork is incomplete (3/8 areas filled): Side Hand."),
        lastClipId = "walk",
        lastView = ViewKind.SIDE_LEFT,
        lastBackgroundArgb = 0xFF223344.toInt(),
        lastSpeed = 1.25f,
    )

    val cases: List<TestCase> = listOf(
        TestCase("a saved character survives a full round trip") {
            val result = Fixtures.process()
            val project = sampleProject(result)
            val json = ProjectCodec.encode(project)
            val restored = ProjectCodec.decode(json)

            Assert.that(restored != null) { "project failed to decode" }
            Assert.equals(project.id, restored!!.id)
            Assert.equals(project.name, restored.name)
            Assert.equals(project.createdAtEpochMillis, restored.createdAtEpochMillis)
            Assert.equals(project.lastOpenedAtEpochMillis, restored.lastOpenedAtEpochMillis)
            Assert.equals(project.sheetFileName, restored.sheetFileName)
            Assert.equals(project.thumbnailFileName, restored.thumbnailFileName)
            Assert.equals(project.availableViews, restored.availableViews)
            Assert.equals(project.mirroredSideView, restored.mirroredSideView)
            Assert.equals(project.availableExpressions, restored.availableExpressions)
            Assert.equals(project.availableMouthShapes, restored.availableMouthShapes)
            Assert.equals(project.notes, restored.notes)
            Assert.equals(project.lastClipId, restored.lastClipId)
            Assert.equals(project.lastView, restored.lastView)
            Assert.equals(project.lastBackgroundArgb, restored.lastBackgroundArgb)
            Assert.close(project.lastSpeed, restored.lastSpeed, 1e-4f, "speed round trips")
            Assert.equals(project.sprites.size, restored.sprites.size, "sprite manifest size")
        },
        TestCase("sprite manifests keep pivots, sizes and rectangles") {
            val result = Fixtures.process()
            val project = sampleProject(result)
            val restored = ProjectCodec.decode(ProjectCodec.encode(project))!!
            for ((index, original) in project.sprites.withIndex()) {
                val copy = restored.sprites[index]
                Assert.equals(original.slotId, copy.slotId, "slot id")
                Assert.equals(original.fileName, copy.fileName, "file name")
                Assert.equals(original.width, copy.width, "width")
                Assert.equals(original.height, copy.height, "height")
                Assert.close(original.pivotX, copy.pivotX, 1e-5f, "${original.slotId} pivot x")
                Assert.close(original.pivotY, copy.pivotY, 1e-5f, "${original.slotId} pivot y")
                Assert.close(original.coverage, copy.coverage, 1e-5f, "${original.slotId} coverage")
                Assert.equals(original.sourceRect, copy.sourceRect, "${original.slotId} source rect")
                Assert.equals(original.contentRect, copy.contentRect, "${original.slotId} content rect")
            }
        },
        TestCase("a reopened project rebuilds an identical rig") {
            val result = Fixtures.process()
            val built = RigBuilder.build(result)
            val project = sampleProject(result).copy(
                availableViews = built.availableViews,
                mirroredSideView = built.availability.mirroredSideView,
            )

            val restored = ProjectCodec.decode(ProjectCodec.encode(project))!!
            val rebuilt = RigBuilder.buildFromAssets(
                restored.spriteAssets(),
                ViewAvailability.from(restored.availableViews, restored.mirroredSideView),
            )

            Assert.equals(built.rigs.keys, rebuilt.keys, "same views after reopening")
            for ((view, original) in built.rigs) {
                val copy = rebuilt[view]!!
                Assert.equals(original.bones.map { it.id }, copy.bones.map { it.id }, "$view bone ids")
                Assert.equals(original.bones.map { it.joint }, copy.bones.map { it.joint }, "$view joints")
                Assert.equals(
                    original.bones.map { it.targetHeight },
                    copy.bones.map { it.targetHeight },
                    "$view proportions",
                )
                Assert.equals(original.bones.map { it.z }, copy.bones.map { it.z }, "$view layering")
                Assert.equals(
                    original.bones.map { it.constraint },
                    copy.bones.map { it.constraint },
                    "$view constraints",
                )
                Assert.equals(original.faceSet.eyes.keys, copy.faceSet.eyes.keys, "$view expressions")
                Assert.equals(original.faceSet.mouths.keys, copy.faceSet.mouths.keys, "$view mouths")
                Assert.equals(original.mirroredFrom, copy.mirroredFrom, "$view mirror origin")
            }
        },
        TestCase("project helpers behave") {
            val result = Fixtures.process()
            val project = sampleProject(result)
            Assert.equals("Renamed", project.renamed("Renamed", 5L).name)
            Assert.equals(5L, project.renamed("Renamed", 5L).updatedAtEpochMillis)
            Assert.equals(7L, project.touched(7L).lastOpenedAtEpochMillis)
            val copy = project.duplicated("proj_02", "Captain Test (copy)", 9L)
            Assert.equals("proj_02", copy.id)
            Assert.equals(9L, copy.createdAtEpochMillis)
            Assert.equals(project.sprites.size, copy.sprites.size, "a duplicate keeps its sprites")
            Assert.that(project.hasView(ViewKind.FRONT)) { "front view recorded" }
            Assert.that(!project.hasView(ViewKind.BACK)) { "back view not recorded" }
            Assert.that(project.hasProfileArtwork) { "profile availability is derived" }
            Assert.that(project.spriteAssets().isNotEmpty()) { "manifest converts to rig assets" }
        },
        TestCase("corrupt project files degrade instead of crashing") {
            Assert.equals(null, ProjectCodec.decode("this is not json"))
            Assert.equals(null, ProjectCodec.decode(""))
            Assert.equals(null, ProjectCodec.decode("{}"), "a project without an id is rejected")
            val brokenSprites = ProjectCodec.decode("{\"id\":\"x\", \"sprites\": \"not a list\"}")
            Assert.that(brokenSprites != null) { "a project with an unreadable sprite list still opens" }
            Assert.equals(0, brokenSprites!!.sprites.size, "broken sprite lists are ignored")
            val partial = ProjectCodec.decode(
                """{"id":"x","name":"","availableViews":["FRONT","NOT_A_VIEW"],"lastSpeed":"fast"}""",
            )
            Assert.that(partial != null) { "a partially valid project should still open" }
            Assert.equals("Character", partial!!.name, "blank names fall back to a default")
            Assert.equals(listOf(ViewKind.FRONT), partial.availableViews, "unknown views are dropped")
            Assert.close(1f, partial.lastSpeed, 1e-6f, "a non numeric speed falls back to 1.0")
            Assert.equals("sheet.png", partial.sheetFileName, "missing fields use defaults")
            Assert.equals(0, partial.sprites.size, "broken sprite lists are ignored")
        },
        TestCase("json reader handles the full grammar") {
            val text = """
            {
              "string": "hello \"world\"\n\té☃",
              "int": 42,
              "negative": -17,
              "float": 1.5e2,
              "true": true,
              "false": false,
              "null": null,
              "array": [1, 2, {"nested": [true]}],
              "empty array": [],
              "empty object": {}
            }
            """.trimIndent()
            val value = Json.parse(text)
            Assert.equals("hello \"world\"\n\té☃", value.string("string"), "escapes and unicode")
            Assert.equals(42, value.int("int"))
            Assert.equals(-17, value.int("negative"))
            Assert.close(150f, value.float("float"), 1e-3f, "exponent notation")
            Assert.equals(true, value.boolean("true"))
            Assert.equals(false, value.boolean("false", true), "explicit false wins over the fallback")
            Assert.equals("", value.string("null"), "null falls back to the default")
            Assert.equals(listOf("a"), Json.parse("""{"k":["a"]}""").stringList("k"))
            Assert.equals(emptyList(), Json.parse("{}").stringList("k"), "missing lists are empty")
            val array = value.get("array") as? JsonValue.Arr
            Assert.equals(3, array?.items?.size ?: -1, "array length")
            val nested = (array?.items?.get(2) as? JsonValue.Obj)?.get("nested") as? JsonValue.Arr
            Assert.equals(true, (nested?.items?.firstOrNull() as? JsonValue.Bool)?.value, "nested values")
            Assert.equals(0, (value.get("empty array") as? JsonValue.Arr)?.items?.size ?: -1)
            Assert.equals(0, (value.get("empty object") as? JsonValue.Obj)?.members?.size ?: -1)

            Assert.throws<JsonParseException> { Json.parse("{") }
            Assert.throws<JsonParseException> { Json.parse("{\"a\":}") }
            Assert.throws<JsonParseException> { Json.parse("[1,2") }
            Assert.throws<JsonParseException> { Json.parse("{\"a\":1} trailing") }
            Assert.equals(null, Json.parseOrNull("nonsense"), "parseOrNull never throws")
        },
        TestCase("json writer round trips and escapes control characters") {
            val original = obj(
                "name" to str("quote\" backslash\\ newline\n tab\t control\u0001"),
                "count" to num(7),
                "ratio" to num(0.25f),
                "big" to num(1_700_000_000_000L),
                "flag" to bool(true),
                "list" to arr(num(1), num(2), str("three")),
                "nested" to obj("inner" to str("value")),
            )
            val text = Json.stringify(original)
            val restored = Json.parse(text)
            Assert.equals(original, restored, "round trip must be lossless")
            Assert.that(!text.contains('\n')) { "control characters must be escaped" }
            Assert.that(text.contains("\\u0001")) { "control characters use \\u escapes" }
            Assert.equals("7", Json.stringify(num(7)), "integers stay integers")
            Assert.equals("null", Json.stringify(JsonValue.Null), "nulls are written as null")

            val pretty = Json.stringify(original, pretty = true)
            Assert.that(pretty.contains("\n  ")) { "pretty printing indents" }
            Assert.equals(original, Json.parse(pretty), "pretty output still parses")
        },
        TestCase("manifest entries convert back into rig sprites") {
            val entry = SpriteManifestEntry(
                slotId = "front_head",
                fileName = "front_head.png",
                width = 320,
                height = 330,
                pivotX = 0.5f,
                pivotY = 0.9f,
                coverage = 0.72f,
                sourceRect = IntRect(70, 70, 320, 330),
                contentRect = IntRect(80, 74, 300, 320),
            )
            val asset = entry.toAsset()
            Assert.equals("front_head", asset.slotId)
            Assert.equals(320, asset.width)
            Assert.close(320f / 330f, asset.aspect, 1e-5f, "aspect drives uniform scaling")
            Assert.close(0.5f, asset.flippedPivot.x, 1e-6f, "a centred pivot is its own mirror")
            Assert.close(0.9f, asset.flippedPivot.y, 1e-6f, "mirroring never moves the pivot vertically")
            Assert.close(0.5f, asset.pivot.x, 1e-6f)
        },
        TestCase("view availability records what the user actually drew") {
            val availability = ViewAvailability.from(
                listOf(ViewKind.FRONT, ViewKind.SIDE_LEFT, ViewKind.SIDE_RIGHT),
                mirroredSideView = true,
            )
            Assert.that(availability.front && availability.sideLeft && availability.sideRight) { "recorded views" }
            Assert.that(!availability.back) { "back view is absent" }
            Assert.that(availability.hasProfile) { "profile artwork is present" }
            Assert.equals(
                listOf(ViewKind.FRONT, ViewKind.SIDE_LEFT, ViewKind.SIDE_RIGHT),
                availability.views,
                "view list keeps template order",
            )
            Assert.equals(ViewAvailability(), ViewAvailability.from(emptyList(), false), "nothing drawn")
            Assert.that(!ViewAvailability().hasProfile) { "no profile by default" }
        },
    )

}
