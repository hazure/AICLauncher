package com.aliceincradle.launcher.ui.icon

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * In-source 24x24 Material-style ImageVector icons for all [IconSpec] variants.
 * Zero res/ additions; each icon is lazily cached and keeps path segments <= 12.
 */
object FileIcons {

    fun iconFor(spec: IconSpec): ImageVector = when (spec) {
        IconSpec.FOLDER -> folderIcon
        IconSpec.PARENT -> parentIcon
        IconSpec.TEXT -> textIcon
        IconSpec.MARKDOWN -> markdownIcon
        IconSpec.CODE -> codeIcon
        IconSpec.CONFIG -> configIcon
        IconSpec.ARCHIVE -> archiveIcon
        IconSpec.IMAGE -> imageIcon
        IconSpec.AUDIO -> audioIcon
        IconSpec.VIDEO -> videoIcon
        IconSpec.PDF -> pdfIcon
        IconSpec.SHEET -> sheetIcon
        IconSpec.BINARY -> binaryIcon
        IconSpec.UNKNOWN -> unknownIcon
    }

    fun contentDescriptionFor(spec: IconSpec): String = when (spec) {
        IconSpec.FOLDER -> "文件夹"
        IconSpec.PARENT -> "上级目录"
        IconSpec.TEXT -> "文本文件"
        IconSpec.MARKDOWN -> "Markdown 文档"
        IconSpec.CODE -> "源代码文件"
        IconSpec.CONFIG -> "配置文件"
        IconSpec.ARCHIVE -> "压缩包"
        IconSpec.IMAGE -> "图片"
        IconSpec.AUDIO -> "音频"
        IconSpec.VIDEO -> "视频"
        IconSpec.PDF -> "PDF 文档"
        IconSpec.SHEET -> "表格文件"
        IconSpec.BINARY -> "二进制文件"
        IconSpec.UNKNOWN -> "未知文件"
    }

    @Composable
    fun tintFor(spec: IconSpec): Color = when (spec) {
        IconSpec.FOLDER, IconSpec.PARENT -> MaterialTheme.colorScheme.primary
        IconSpec.CODE -> MaterialTheme.colorScheme.tertiary
        IconSpec.CONFIG -> MaterialTheme.colorScheme.secondary
        IconSpec.MARKDOWN, IconSpec.TEXT -> MaterialTheme.colorScheme.onSurfaceVariant
        IconSpec.ARCHIVE, IconSpec.PDF -> MaterialTheme.colorScheme.error
        IconSpec.BINARY -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    private fun buildIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
    }

    private val folderIcon by lazy {
        buildIcon("FileFolder") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 4f)
                lineTo(4f, 4f)
                curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
                lineTo(2f, 18f)
                curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
                lineTo(20f, 20f)
                curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
                lineTo(22f, 8f)
                curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
                lineTo(12f, 6f)
                close()
            }
        }
    }

    private val parentIcon by lazy {
        buildIcon("FileParent") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(20f, 18f)
                lineTo(4f, 18f)
                lineTo(4f, 6f)
                lineTo(10f, 6f)
                lineTo(12f, 8f)
                lineTo(20f, 8f)
                close()
                moveTo(12f, 15f)
                lineTo(12f, 10f)
                moveTo(9.5f, 12.5f)
                lineTo(12f, 10f)
                lineTo(14.5f, 12.5f)
            }
        }
    }

    private val textIcon by lazy {
        buildIcon("FileText") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 2f)
                lineTo(14f, 2f)
                lineTo(19f, 7f)
                lineTo(19f, 22f)
                lineTo(6f, 22f)
                close()
                moveTo(9f, 12f)
                lineTo(16f, 12f)
                moveTo(9f, 16f)
                lineTo(16f, 16f)
            }
        }
    }

    private val markdownIcon by lazy {
        buildIcon("FileMarkdown") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 5f)
                lineTo(20f, 5f)
                lineTo(20f, 19f)
                lineTo(4f, 19f)
                close()
                moveTo(7f, 15f)
                lineTo(7f, 9f)
                lineTo(10f, 12.5f)
                lineTo(13f, 9f)
                lineTo(13f, 15f)
                moveTo(16.5f, 9f)
                lineTo(16.5f, 15f)
            }
        }
    }

    private val codeIcon by lazy {
        buildIcon("FileCode") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9f, 7f)
                lineTo(4f, 12f)
                lineTo(9f, 17f)
                moveTo(15f, 7f)
                lineTo(20f, 12f)
                lineTo(15f, 17f)
                moveTo(13f, 5f)
                lineTo(11f, 19f)
            }
        }
    }

    private val configIcon by lazy {
        buildIcon("FileConfig") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 6f)
                lineTo(20f, 6f)
                moveTo(4f, 12f)
                lineTo(20f, 12f)
                moveTo(4f, 18f)
                lineTo(20f, 18f)
                moveTo(9f, 4f)
                lineTo(9f, 8f)
                moveTo(15f, 10f)
                lineTo(15f, 14f)
                moveTo(10f, 16f)
                lineTo(10f, 20f)
            }
        }
    }

    private val archiveIcon by lazy {
        buildIcon("FileArchive") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(4f, 20f)
                close()
                moveTo(12f, 4f)
                lineTo(12f, 15f)
                moveTo(10f, 8f)
                lineTo(14f, 8f)
                moveTo(10f, 12f)
                lineTo(14f, 12f)
            }
        }
    }

    private val imageIcon by lazy {
        buildIcon("FileImage") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(4f, 20f)
                close()
                moveTo(5f, 17f)
                lineTo(10f, 12f)
                lineTo(14f, 16f)
                lineTo(17f, 13f)
                lineTo(19f, 17f)
            }
        }
    }

    private val audioIcon by lazy {
        buildIcon("FileAudio") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(10f, 17f)
                lineTo(10f, 6f)
                lineTo(18f, 4f)
                lineTo(18f, 15f)
                moveTo(7f, 17f)
                lineTo(10f, 17f)
                moveTo(15f, 15f)
                lineTo(18f, 15f)
            }
        }
    }

    private val videoIcon by lazy {
        buildIcon("FileVideo") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 5f)
                lineTo(20f, 5f)
                lineTo(20f, 19f)
                lineTo(4f, 19f)
                close()
                moveTo(10f, 9f)
                lineTo(16f, 12f)
                lineTo(10f, 15f)
                close()
            }
        }
    }

    private val pdfIcon by lazy {
        buildIcon("FilePdf") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 2f)
                lineTo(14f, 2f)
                lineTo(19f, 7f)
                lineTo(19f, 22f)
                lineTo(6f, 22f)
                close()
                moveTo(9f, 15f)
                lineTo(15f, 15f)
                moveTo(9f, 12f)
                lineTo(13f, 12f)
            }
        }
    }

    private val sheetIcon by lazy {
        buildIcon("FileSheet") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 4f)
                lineTo(20f, 4f)
                lineTo(20f, 20f)
                lineTo(4f, 20f)
                close()
                moveTo(4f, 10f)
                lineTo(20f, 10f)
                moveTo(10f, 4f)
                lineTo(10f, 20f)
            }
        }
    }

    private val binaryIcon by lazy {
        buildIcon("FileBinary") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(5f, 4f)
                lineTo(19f, 4f)
                lineTo(19f, 20f)
                lineTo(5f, 20f)
                close()
                moveTo(9f, 9f)
                lineTo(9f, 15f)
                moveTo(13f, 9f)
                lineTo(15f, 9f)
                lineTo(15f, 15f)
                lineTo(13f, 15f)
                close()
            }
        }
    }

    private val unknownIcon by lazy {
        buildIcon("FileUnknown") {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 2f)
                lineTo(14f, 2f)
                lineTo(19f, 7f)
                lineTo(19f, 22f)
                lineTo(6f, 22f)
                close()
                moveTo(10f, 11f)
                curveTo(10f, 9.5f, 14f, 9.5f, 14f, 11.5f)
                lineTo(12f, 13.5f)
                moveTo(12f, 17f)
                lineTo(12f, 17.5f)
            }
        }
    }
}
