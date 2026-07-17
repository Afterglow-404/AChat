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
fun ProfilePage(
    onBack: () -> Unit,
    profileName: String, onProfileNameChange: (String) -> Unit,
    profileWechatId: String, onProfileWechatIdChange: (String) -> Unit,
    profileAvatarUri: String, onPickProfileAvatar: () -> Unit, onClearProfileAvatar: () -> Unit
) {
    SubPageScaffold("个人信息", onBack) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(AchatTheme.colors.divider), contentAlignment = Alignment.Center) {
                if (profileAvatarUri.isNotEmpty()) {
                    val bmp = remember(profileAvatarUri) { try { BitmapFactory.decodeFile(profileAvatarUri)?.asImageBitmap() } catch (_: Exception) { null } }
                    if (bmp != null) Image(bmp, null, Modifier.size(56.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else Text(profileName.take(1), fontSize = 20.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
                } else Text(profileName.take(1), fontSize = 20.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
            }
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = onPickProfileAvatar) { Text(if (profileAvatarUri.isNotEmpty()) "更换头像" else "选择头像", color = AchatTheme.colors.primary) }
            if (profileAvatarUri.isNotEmpty()) TextButton(onClick = onClearProfileAvatar) { Text("清除", color = AchatTheme.colors.onSurface.copy(alpha = 0.4f)) }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = AchatTheme.colors.divider)
        TextFieldRow("昵称", "User", profileName, onProfileNameChange)
        TextFieldRow("个人签名", "Hello Wisp", profileWechatId, onProfileWechatIdChange)
    }
}
