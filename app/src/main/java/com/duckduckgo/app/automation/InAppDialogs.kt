package com.duckduckgo.app.automation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme

@Composable
internal fun InAppConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(
                    color = DuckDuckGoTheme.colors.backgrounds.surface,
                    shape = RoundedCornerShape(16.dp),
                )
                .clickable { /* consume clicks inside */ }
                .padding(20.dp),
        ) {
            Text(
                text = title,
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = DuckDuckGoTheme.colors.text.secondary,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = cancelLabel,
                    color = DuckDuckGoTheme.colors.brand.accentBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = confirmLabel,
                    color = DuckDuckGoTheme.colors.text.destructive,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun InAppInputDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fieldColors = TextFieldDefaults.colors(
        focusedTextColor = DuckDuckGoTheme.colors.text.primary,
        unfocusedTextColor = DuckDuckGoTheme.colors.text.primary,
        focusedLabelColor = DuckDuckGoTheme.colors.text.secondary,
        unfocusedLabelColor = DuckDuckGoTheme.colors.text.secondary,
        cursorColor = DuckDuckGoTheme.colors.brand.accentBlue,
        focusedContainerColor = DuckDuckGoTheme.colors.backgrounds.background,
        unfocusedContainerColor = DuckDuckGoTheme.colors.backgrounds.background,
        focusedIndicatorColor = DuckDuckGoTheme.colors.brand.accentBlue,
        unfocusedIndicatorColor = DuckDuckGoTheme.colors.text.secondary,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(
                    color = DuckDuckGoTheme.colors.backgrounds.surface,
                    shape = RoundedCornerShape(16.dp),
                )
                .clickable { }
                .padding(20.dp),
        ) {
            Text(
                text = title,
                color = DuckDuckGoTheme.colors.text.primary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = fieldColors,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    text = cancelLabel,
                    color = DuckDuckGoTheme.colors.brand.accentBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = confirmLabel,
                    color = DuckDuckGoTheme.colors.brand.accentBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}