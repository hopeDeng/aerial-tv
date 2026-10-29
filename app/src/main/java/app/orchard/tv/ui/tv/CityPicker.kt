package app.orchard.tv.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.Cities
import app.orchard.tv.weather.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CityPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cities by remember { mutableStateOf<List<Cities.City>>(emptyList()) }
    var province by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        cities = withContext(Dispatchers.IO) { Cities.load(context) }
    }
    LaunchedEffect(province, cities) {
        if (cities.isNotEmpty()) runCatching { first.requestFocus() }
    }

    val provinces = remember(cities) {
        cities.filter { it.province.isNotEmpty() }
            .groupBy { it.province }
            .toList()
            .sortedWith(compareBy({ (_, list) -> list.minOf { it.rank } }, { it.first }))
    }
    val shown = province?.let { p ->
        cities.filter { it.province == p }.sortedWith(compareBy({ it.rank }, { it.name }))
    }

    BackHandler {
        if (province != null) province = null else onDismiss()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .consumeTaps(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(560.dp)
                .height(420.dp)
                .liquidGlass(24.dp)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(
                stringResource(if (province == null) R.string.tv_city_province else R.string.tv_city_pick),
                style = Type.Title.copy(color = Color.White),
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
            Weather.place?.let {
                BasicText(
                    stringResource(R.string.tv_city_current, it),
                    style = Type.Callout.copy(color = Color.White.copy(alpha = 0.6f)),
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (province == null) {
                    provinces.forEachIndexed { index, (name, list) ->
                        CityRow(name, index == 0, first) {
                            province = name
                        }
                    }
                } else {
                    shown.orEmpty().forEachIndexed { index, city ->
                        CityRow(city.name, index == 0, first) {
                            scope.launch {
                                Weather.choose(city.name)
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CityRow(name: String, takeFocus: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    Pressable(
        onClick = onClick,
        focusRequester = if (takeFocus) requester else null,
        focusedScale = 1.02f,
        modifier = Modifier.fillMaxWidth(),
    ) { state ->
        BasicText(
            name,
            style = Type.Body.copy(color = if (state.focused) Palette.OnFocus else Color.White.copy(alpha = 0.8f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (state.focused) Palette.Focus else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}
