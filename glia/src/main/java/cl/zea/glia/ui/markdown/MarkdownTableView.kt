package cl.zea.glia.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cl.zea.glia.ui.GliaTheme

@Composable
fun MarkdownTableView(
    table: MarkdownBlock.Table,
    theme: GliaTheme,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, theme.tableBorder, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
    ) {
        Column {
            // Header Row
            if (table.headers.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .background(theme.tableBorder.copy(alpha = 0.25f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    table.headers.forEach { header ->
                        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                            MarkdownTextView(
                                content = header,
                                color = color,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                HorizontalDivider(color = theme.tableBorder, thickness = 1.dp)
            }

            // Data Rows
            table.rows.forEachIndexed { index, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    row.forEach { cell ->
                        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                            MarkdownTextView(
                                content = cell,
                                color = color,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                if (index < table.rows.lastIndex) {
                    HorizontalDivider(color = theme.tableBorder.copy(alpha = 0.3f), thickness = 0.5.dp)
                }
            }
        }
    }
}
