package com.aftglw.devapi.feature.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.ui.theme.*
import com.aftglw.devapi.ui.buildCustomTypography
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun BackgroundsPage(
    onBack: () -> Unit,
    mainBgUri: String, onPickMainBg: () -> Unit, onResetMainBg: () -> Unit,
    chatBgUri: String, onPickChatBg: () -> Unit, onResetChatBg: () -> Unit
) {
    SubPageScaffold("背景设置", onBack) {
        Spacer(Modifier.height(8.dp))
        BgRow("主界面背景", mainBgUri, onPickMainBg, onResetMainBg)
        HorizontalDivider(Modifier.padding(start = 16.dp), color = Color(0xFFF0F0F0))
        BgRow("聊天背景", chatBgUri, onPickChatBg, onResetChatBg)
    }
}
