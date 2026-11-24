package com.kadaliao.kaixinguo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * SettingsDialog is the settings: the signing service playback falls back
 * to when the built-in signing stops working. Leaving it while the service
 * is being checked saves nothing.
 */
@Composable
fun SettingsDialog(close: () -> Unit) {
    val model = LocalModel.current
    val form = remember { model.signerForm() }
    val scope = rememberCoroutineScope()
    val save = { scope.launch { if (form.save()) close() } }
    val urlError = form.urlError
    AlertDialog(
        onDismissRequest = close,
        title = { Text("设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("签名服务", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "红果改了签名校验时，内置签名可能暂时播不了。这时开心果会改用你部署的 openhongguo-signer 签名服务取播放地址，其他请求不经过它。不需要时留空。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = form.url,
                    onValueChange = form::editUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("地址") },
                    placeholder = { Text("https://signer.example.com") },
                    singleLine = true,
                    isError = urlError.isNotEmpty(),
                    supportingText = if (urlError.isNotEmpty()) ({ Text(urlError) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = form.token,
                    onValueChange = form::editToken,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Token") },
                    placeholder = { Text("签名服务没有设 token 时留空") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                )
                if (form.error.isNotEmpty()) {
                    Text(form.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !form.checking && urlError.isEmpty()) {
                Text(
                    when {
                        form.checking -> "正在检查…"
                        form.unsure -> "仍然保存"
                        else -> "保存"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = close) { Text("取消") } },
    )
}
