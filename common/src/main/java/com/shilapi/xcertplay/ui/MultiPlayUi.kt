package com.shilapi.xcertplay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shilapi.xcertplay.host.R

/** XiaoSha MultiPlay's palette: the blue of the phone's call screen on near-black. */
internal object MultiPlayColors {
    val Background = Color(0xFF141519)
    val Surface = Color(0xFF222428)
    val Border = Color(0xFF2F4459)
    val Accent = Color(0xFF6EC1FF)
    val OnAccent = Color(0xFF081420)
    val Text = Color(0xFFF1F5FC)
    val Muted = Color(0xFF9AA0AA)
    val Warning = Color(0xFFFFC480)
}

/** One row of a settings group. The activity builds these from its saved preferences. */
internal sealed interface SettingItem {
    data class Link(val title: String, val value: String?, val onClick: () -> Unit) : SettingItem
    data class Toggle(val title: String, val description: String?, val checked: Boolean, val onChange: (Boolean) -> Unit) : SettingItem
    data class Radio(val title: String, val description: String?, val selected: Boolean, val onClick: () -> Unit) : SettingItem
    data class Note(val text: String, val warning: Boolean = false) : SettingItem
    data class Action(val title: String, val primary: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit) : SettingItem
}

internal data class SettingGroup(val title: String, val items: List<SettingItem>)

internal data class HomeModel(
    val carPlayStatus: String,
    val carPlayRunning: Boolean,
    val carPlayWireless: Boolean,
    val carPlayEnabled: Boolean,
    val carPlayNotice: Pair<String, () -> Unit>?,
    val androidAutoConnected: Boolean,
    val androidAutoMethod: Int,
    val setupError: Pair<String, () -> Unit>?,
    val version: String,
)

internal class HomeActions(
    val carPlayConnect: () -> Unit,
    val carPlayMethod: (wireless: Boolean) -> Unit,
    val carPlayDisconnect: () -> Unit,
    val androidAutoConnect: () -> Unit,
    val androidAutoMethod: (index: Int) -> Unit,
)

@Composable
internal fun MultiPlayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MultiPlayColors.Accent, onPrimary = MultiPlayColors.OnAccent,
            background = MultiPlayColors.Background, surface = MultiPlayColors.Surface,
            onBackground = MultiPlayColors.Text, onSurface = MultiPlayColors.Text,
        ),
        content = content,
    )
}

/** The whole screen: header, then the page, on one scrolling column. */
@Composable
internal fun MultiPlayScreen(
    title: String,
    home: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onExit: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MultiPlayColors.Background)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                if (home) {
                    Image(painterResource(R.drawable.ic_multiplay_logo), null, Modifier.size(36.dp))
                } else {
                    RoundIcon(R.drawable.ic_back_arrow, stringResource(R.string.back), onBack)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, color = MultiPlayColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (home) {
                    RoundIcon(R.drawable.ic_settings_gear, stringResource(R.string.settings), onSettings)
                    Spacer(Modifier.width(10.dp))
                    RoundIcon(R.drawable.ic_power, stringResource(R.string.exit), onExit)
                }
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun RoundIcon(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).background(MultiPlayColors.Surface, RoundedCornerShape(24.dp))
            .border(1.dp, MultiPlayColors.Border, RoundedCornerShape(24.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(painterResource(icon), description, tint = MultiPlayColors.Text, modifier = Modifier.size(22.dp)) }
}

@Composable
internal fun HomePage(model: HomeModel, actions: HomeActions) {
    Text(stringResource(R.string.home_title), color = MultiPlayColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.home_subtitle), color = MultiPlayColors.Muted, fontSize = 15.sp)
    Spacer(Modifier.height(20.dp))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 640.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { CarPlayCard(model, actions) }
                Box(Modifier.weight(1f)) { AndroidAutoCard(model, actions) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CarPlayCard(model, actions)
                AndroidAutoCard(model, actions)
            }
        }
    }
    model.setupError?.let { (text, fix) -> Spacer(Modifier.height(16.dp)); Notice(text, fix) }
    Spacer(Modifier.height(24.dp))
    Text("${stringResource(R.string.home_public_preview)}${model.version}", color = MultiPlayColors.Muted, fontSize = 12.sp)
}

@Composable
private fun CarPlayCard(model: HomeModel, actions: HomeActions) = ProjectionCard(
    device = stringResource(R.string.carplay_card_device), title = stringResource(R.string.carplay), status = model.carPlayStatus,
) {
    val method = stringResource(if (model.carPlayWireless) R.string.method_wireless else R.string.method_usb)
    PrimaryButton(if (model.carPlayRunning) stringResource(R.string.open_carplay) else "${stringResource(R.string.connect)} · $method",
        enabled = model.carPlayEnabled, onClick = actions.carPlayConnect)
    MethodChips(listOf(stringResource(R.string.method_wireless), stringResource(R.string.method_usb)),
        if (model.carPlayWireless) 0 else 1) { actions.carPlayMethod(it == 0) }
    if (model.carPlayRunning) {
        Spacer(Modifier.height(10.dp))
        SecondaryButton(stringResource(R.string.disconnect), onClick = actions.carPlayDisconnect)
    }
    model.carPlayNotice?.let { (text, fix) -> Spacer(Modifier.height(14.dp)); Notice(text, fix) }
}

@Composable
private fun AndroidAutoCard(model: HomeModel, actions: HomeActions) {
    val methods = listOf(stringResource(R.string.method_wireless), stringResource(R.string.method_usb), stringResource(R.string.method_self_mode))
    ProjectionCard(
        device = stringResource(R.string.android_auto_card_device), title = stringResource(R.string.android_auto),
        status = stringResource(if (model.androidAutoConnected) R.string.android_auto_connected else R.string.android_auto_hint),
    ) {
        PrimaryButton(if (model.androidAutoConnected) stringResource(R.string.open_android_auto)
            else "${stringResource(R.string.connect)} · ${methods[model.androidAutoMethod]}", onClick = actions.androidAutoConnect)
        if (!model.androidAutoConnected) MethodChips(methods, model.androidAutoMethod, actions.androidAutoMethod)
    }
}

@Composable
private fun ProjectionCard(device: String, title: String, status: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MultiPlayColors.Surface, RoundedCornerShape(24.dp))
            .border(1.dp, MultiPlayColors.Border, RoundedCornerShape(24.dp)).padding(22.dp),
    ) {
        Text(device.uppercase(), color = MultiPlayColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
        Text(title, color = MultiPlayColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        Text(status, color = MultiPlayColors.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        content()
    }
}

/** The ways a card can connect; [current] is outlined as the remembered one. */
@Composable
private fun MethodChips(options: List<String>, current: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, title ->
            val selected = index == current
            OutlinedButton(
                onClick = { onPick(index) },
                modifier = Modifier.weight(1f).height(40.dp),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, if (selected) MultiPlayColors.Accent else MultiPlayColors.Border),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selected) MultiPlayColors.Accent else MultiPlayColors.Muted),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) { Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun PrimaryButton(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MultiPlayColors.Accent, contentColor = MultiPlayColors.OnAccent),
    ) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun SecondaryButton(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MultiPlayColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MultiPlayColors.Text),
    ) { Text(title, fontSize = 16.sp) }
}

/** A tappable warning strip: what is wrong, and the tap opens the fix. */
@Composable
private fun Notice(text: String, onClick: () -> Unit) {
    Text("$text  ›", color = MultiPlayColors.Warning, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().background(MultiPlayColors.Warning.copy(alpha = 0.11f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp))
}

/** A page of grouped rows (settings, connection setup, about). */
@Composable
internal fun GroupedPage(subtitle: String?, groups: List<SettingGroup>) {
    subtitle?.let { Text(it, color = MultiPlayColors.Muted, fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp)) }
    groups.forEach { group ->
        Text(group.title, color = MultiPlayColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
        Column(
            Modifier.fillMaxWidth().background(MultiPlayColors.Surface, RoundedCornerShape(20.dp))
                .border(1.dp, MultiPlayColors.Border, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 4.dp),
        ) {
            group.items.forEachIndexed { index, item ->
                if (index > 0 && item !is SettingItem.Note && group.items[index - 1] !is SettingItem.Note) {
                    HorizontalDivider(color = MultiPlayColors.Border.copy(alpha = 0.6f), thickness = 1.dp)
                }
                SettingRow(item)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun SettingRow(item: SettingItem) {
    when (item) {
        is SettingItem.Link -> Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = item.onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.title, color = MultiPlayColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                item.value?.let { Text(it, color = MultiPlayColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) }
            }
            Text("›", color = MultiPlayColors.Muted, fontSize = 22.sp)
        }
        is SettingItem.Toggle -> Row(
            Modifier.fillMaxWidth().clickable { item.onChange(!item.checked) }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(item.title, color = MultiPlayColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                item.description?.let { Text(it, color = MultiPlayColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) }
            }
            Switch(
                checked = item.checked, onCheckedChange = item.onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MultiPlayColors.OnAccent, checkedTrackColor = MultiPlayColors.Accent,
                    uncheckedThumbColor = MultiPlayColors.Muted, uncheckedTrackColor = MultiPlayColors.Background,
                    uncheckedBorderColor = MultiPlayColors.Border,
                ),
            )
        }
        is SettingItem.Radio -> Row(
            Modifier.fillMaxWidth().clickable(onClick = item.onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(item.title, color = if (item.selected) MultiPlayColors.Accent else MultiPlayColors.Text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                item.description?.let { Text(it, color = MultiPlayColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp)) }
            }
            Box(
                Modifier.size(22.dp).border(2.dp, if (item.selected) MultiPlayColors.Accent else MultiPlayColors.Border, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center,
            ) { if (item.selected) Box(Modifier.size(10.dp).background(MultiPlayColors.Accent, RoundedCornerShape(5.dp))) }
        }
        is SettingItem.Note -> Text(item.text, color = if (item.warning) MultiPlayColors.Warning else MultiPlayColors.Muted, fontSize = 14.sp,
            modifier = Modifier.padding(vertical = 10.dp))
        is SettingItem.Action -> Box(Modifier.padding(vertical = 8.dp)) {
            if (item.primary) PrimaryButton(item.title, item.enabled, item.onClick) else SecondaryButton(item.title, item.enabled, item.onClick)
        }
    }
}
