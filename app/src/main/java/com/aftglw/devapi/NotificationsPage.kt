package com.aftglw.devapi

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
fun NotificationsPage(
    onBack: () -> Unit,
    sound: Boolean, onSoundChange: (Boolean) -> Unit,
    vibrate: Boolean, onVibrateChange: (Boolean) -> Unit
) {
    SubPageScaffold("通知设置", onBack) {
        Spacer(Modifier.height(8.dp))
        SettingsMainHeader("通知")
        ToggleRow("新消息通知", "接收新消息时播放提示音", sound, onSoundChange)
        ToggleRow("振动", "新消息时振动", vibrate, onVibrateChange)
    }
}
