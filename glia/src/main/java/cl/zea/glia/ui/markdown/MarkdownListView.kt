package cl.zea.glia.ui.markdown

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownListItemView(
    item: MarkdownBlock.ListItem,
    color: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        val prefix = if (item.isOrdered) "${item.number}." else "•"
        Text(
            text = prefix,
            color = color,
            fontSize = 15.sp,
            fontWeight = if (item.isOrdered) FontWeight.Medium else FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, end = 8.dp)
        )
        MarkdownTextView(
            content = item.content,
            color = color,
            modifier = Modifier.weight(1f)
        )
    }
}
