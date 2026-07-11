package com.aftglw.devapi.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aftglw.devapi.ui.theme.*

@Composable
fun SettingsMainHeader(title: String) {
    Text(title, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = AchatTheme.colors.onSurface)
            Text(subtitle, fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AchatTheme.colors.primary))
    }
}

@Composable
fun TextFieldRow(label: String, placeholder: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), placeholder = { Text(placeholder, fontSize = 14.sp) },
            singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AchatTheme.colors.primary, unfocusedBorderColor = AchatTheme.colors.divider, focusedContainerColor = AchatTheme.colors.surface, unfocusedContainerColor = AchatTheme.colors.surface))
    }
}

@Composable
fun PasswordRow(label: String, placeholder: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp), placeholder = { Text(placeholder, fontSize = 14.sp) },
            singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AchatTheme.colors.primary, unfocusedBorderColor = AchatTheme.colors.divider, focusedContainerColor = AchatTheme.colors.surface, unfocusedContainerColor = AchatTheme.colors.surface),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    }
}

@Composable
fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = AchatTheme.colors.onSurface)
            Text(subtitle, fontSize = 13.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.5f))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "进入", tint = AchatTheme.colors.onSurface.copy(alpha = 0.3f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPageScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        CenterAlignedTopAppBar(
            title = { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AchatTheme.colors.onSurface) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AchatTheme.colors.onSurface) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.statusBarsPadding())
        HorizontalDivider(thickness = 0.5.dp, color = AchatTheme.colors.divider)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
fun BgRow(label: String, uri: String, onPick: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(AchatTheme.colors.surface).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.3f), fontSize = 14.sp, color = AchatTheme.colors.onSurface.copy(alpha = 0.6f))
        Text(if (uri.isNotEmpty()) "已设置 ✓" else "未设置", Modifier.weight(0.4f), fontSize = 13.sp, color = if (uri.isNotEmpty()) AchatTheme.colors.primary else AchatTheme.colors.onSurface.copy(alpha = 0.3f))
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onPick, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) { Text("选择", fontSize = 11.sp) }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onReset, modifier = Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("重置", fontSize = 11.sp, color = Color.Gray) }
    }
}
