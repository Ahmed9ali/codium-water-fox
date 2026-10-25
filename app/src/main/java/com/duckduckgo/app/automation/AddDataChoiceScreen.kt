/*
 * Copyright (c) 2025 DuckDuckGo
 * ... license header ...
 */

package com.duckduckgo.app.automation

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duckduckgo.app.browser.R
import com.duckduckgo.common.ui.compose.theme.DuckDuckGoTheme
import com.duckduckgo.mobile.android.R.drawable

@Composable
fun AddDataChoiceScreen(
    type: DataGroupType, // NEW: Required to know what we are adding
    onBack: () -> Unit,
    onPasteClick: () -> Unit,
    onFileLoaded: (String, String) -> Unit,
    onGenerateClick: () -> Unit,
) {
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: ""
            if (text.isNotBlank()) {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                    ?: context.getString(R.string.automation_imported_group)
                onFileLoaded(name, text)
            }
        } catch (e: Exception) {
            Toast.makeText(context, R.string.automation_file_load_error, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DuckDuckGoTheme.colors.backgrounds.background),
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DuckDuckGoTheme.colors.backgrounds.surface)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(drawable.ic_arrow_left_24),
                    contentDescription = stringResource(R.string.automation_back),
                    tint = DuckDuckGoTheme.colors.icons.primary,
                )
            }
            Text(
                text = stringResource(R.string.automation_add_data_title),
                color = DuckDuckGoTheme.colors.text.primary,
                fontFamily = VarelaRound,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Column(modifier = Modifier.padding(16.dp)) {
            // Type Indicator Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (type == DataGroupType.DEFAULT) Color(0xFFE0F2FE) else Color(0xFFFCE7F3),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                Column {
                    Text(
                        text = if (type == DataGroupType.DEFAULT) "Creating Default Data" else "Creating Recurring Data",
                        color = if (type == DataGroupType.DEFAULT) Color(0xFF0369A1) else Color(0xFFBE185D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        fontFamily = VarelaRound
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (type == DataGroupType.DEFAULT) {
                            "This data is reusable and will never run out (e.g., Password, DOB)."
                        } else {
                            "This data is consumed 1-by-1 (e.g., Emails, Phone numbers)."
                        },
                        color = if (type == DataGroupType.DEFAULT) Color(0xFF0369A1).copy(alpha = 0.8f) else Color(0xFFBE185D).copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        fontFamily = VarelaRound
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            ChoiceCard(
                iconRes = drawable.ic_document_download_24,
                title = stringResource(R.string.automation_paste_manually),
                desc = stringResource(R.string.automation_paste_manually_desc),
                onClick = onPasteClick,
            )
            Spacer(Modifier.height(12.dp))
            ChoiceCard(
                iconRes = drawable.ic_downloads_24,
                title = stringResource(R.string.automation_upload_txt),
                desc = stringResource(R.string.automation_upload_txt_desc),
                onClick = { filePicker.launch("text/plain") },
            )
            Spacer(Modifier.height(12.dp))
            ChoiceCard(
                iconRes = drawable.ic_automation_24,
                title = stringResource(R.string.automation_generate_form_data),
                desc = stringResource(R.string.automation_generate_form_data_desc),
                onClick = onGenerateClick,
            )
        }
    }
}

@Composable
private fun ChoiceCard(
    iconRes: Int,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = DuckDuckGoTheme.colors.backgrounds.surface,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = DuckDuckGoTheme.colors.brand.accentBlue,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DuckDuckGoTheme.colors.text.primary,
                fontFamily = VarelaRound,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                color = DuckDuckGoTheme.colors.text.secondary,
                fontFamily = VarelaRound,
                fontSize = 13.sp,
            )
        }
    }
}