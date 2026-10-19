package com.tayga16.s5landbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.tayga16.s5landbox.data.EngineRepository
import com.tayga16.s5landbox.data.EngineStatus
import com.tayga16.s5landbox.data.MachineOptions
import com.tayga16.s5landbox.data.NetworkStatus
import com.tayga16.s5landbox.data.OptionRow
import com.tayga16.s5landbox.data.OptionsCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    repository: EngineRepository,
    machineId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var machineName by remember { mutableStateOf("Machine") }
    var catalog by remember { mutableStateOf<OptionsCatalog?>(null) }
    var values by remember { mutableStateOf(BooleanArray(0)) }
    var savedValues by remember { mutableStateOf(BooleanArray(0)) }
    var machineOptions by remember { mutableStateOf<MachineOptions?>(null) }
    var status by remember { mutableStateOf<EngineStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    val defaults = catalog?.options?.map { it.default }?.toBooleanArray() ?: BooleanArray(0)

    LaunchedEffect(machineId) {
        val loaded = withContext(Dispatchers.IO) {
            val machine = repository.machines().firstOrNull { it.id == machineId }
            val options = repository.options()
            val machineOptions = repository.machineOptions(machineId)
            Triple(machine?.name ?: "Machine", options, machineOptions)
        }
        machineName = loaded.first
        catalog = loaded.second
        machineOptions = loaded.third
        val fallback = loaded.second.options.map { it.default }.toBooleanArray()
        val current =
            if (loaded.third.values.size == fallback.size) loaded.third.values else fallback
        values = current
        savedValues = current.copyOf()
        loading = false
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            status = withContext(Dispatchers.Default) { repository.status() }
            delay(1_000)
        }
    }

    val runningHere = status?.let { !it.idle && it.instanceId == machineId } ?: false
    val dirty = values.size == savedValues.size &&
        values.indices.any { values[it] != savedValues[it] }

    fun toggle(index: Int, next: Boolean) {
        if (runningHere) return
        values = values.copyOf().also { if (index in it.indices) it[index] = next }
    }

    fun save(replacement: BooleanArray) {
        if (saving || runningHere) return
        scope.launch {
            saving = true
            val result = withContext(Dispatchers.IO) {
                repository.setMachineValues(machineId, replacement)
            }
            saving = false
            if (result.ok) {
                values = replacement.copyOf()
                savedValues = replacement.copyOf()
                snackbar.showSnackbar("Options saved.")
            } else {
                snackbar.showSnackbar(
                    result.detail.ifEmpty { "The options could not be saved." },
                )
            }
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = "Settings",
                subtitle = machineName,
                onBack = onBack,
                actions = {
                    val canSave = dirty && !runningHere && !saving && catalog != null
                    IconButton(
                        onClick = { save(values.copyOf()) },
                        enabled = canSave,
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Save options")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (loading || catalog == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }
        } else {
            val options = catalog ?: OptionsCatalog(emptyList(), emptyList())
            Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (runningHere) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "The machine is running. Options are read when it starts, " +
                            "so stop it before changing them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            /* The switches under OPTIONS say what the next boot will ask for;
             * this card pairs them with what the machine's image can actually
             * deliver, so "on" never silently means "still offline". */
            GuestInternetCard(
                values = values,
                options = options.options,
                witnesses = machineOptions,
                runningHere = runningHere,
                live = status?.takeIf { !it.idle && it.instanceId == machineId }?.network,
            )

            Text(
                text = "OPTIONS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )

            for (groupIndex in options.groups.indices) {
                val group = options.groups[groupIndex]
                val rows = options.options.withIndex().filter { it.value.group == groupIndex }
                if (rows.isEmpty()) continue
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        if (group.title.isNotBlank()) {
                            Text(group.title, style = MaterialTheme.typography.titleMedium)
                        }
                        if (group.note.isNotBlank()) {
                            Text(
                                text = group.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        for ((index, option) in rows) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                                    .alpha(if (option.implemented) 1f else 0.55f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(
                                        text = option.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (option.detail.isNotBlank()) {
                                        Text(
                                            text = option.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (!option.implemented) {
                                        Text(
                                            text = "Not available on this build",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                Switch(
                                    checked = values.getOrElse(index) { option.default },
                                    onCheckedChange = { toggle(index, it) },
                                    enabled = option.implemented && !runningHere,
                                )
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { save(values.copyOf()) },
                    enabled = dirty && !runningHere && !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save")
                }
                OutlinedButton(
                    onClick = { save(defaults) },
                    enabled = !runningHere && !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Defaults")
                }
            }

            Text(
                text = "Options are copied into the machine when it starts. Changes here do " +
                    "not touch an emulator that is already running.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
        }
    }
}

@Composable
private fun GuestInternetCard(
    values: BooleanArray,
    options: List<OptionRow>,
    witnesses: MachineOptions?,
    runningHere: Boolean,
    live: NetworkStatus?,
) {
    val pppIndex = options.indexOfFirst { it.name == "ppp" }
    val natIndex = options.indexOfFirst { it.name == "nat" }
    val pppOn = pppIndex >= 0 && values.getOrElse(pppIndex) { false }
    val natOn = natIndex >= 0 && values.getOrElse(natIndex) { false }
    val pppProvisioned = witnesses?.pppProvisioned ?: false
    val diskGiB = witnesses?.diskGiB ?: 0L

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("Guest internet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    runningHere && live != null && live.onNetwork ->
                        "On the network now — PPP is up" +
                            (if (live.nat) " and traffic is routed" else "") + "."
                    runningHere && live != null && live.attached ->
                        "PPP is negotiating; the link has not come up yet."
                    pppOn && pppProvisioned ->
                        "Ready. The link comes up when this machine starts."
                    pppProvisioned ->
                        "The image supports it, but the networking switch is off."
                    else ->
                        "This machine's image was made without the guest-network " +
                            "record, so it can never link. Create a fresh machine."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (runningHere && live != null && live.onNetwork) {
                Text(
                    text = "DNS ${live.dnsAnswered}/${live.dnsQueries} answered" +
                        (if (live.dnsTimeouts > 0) " · ${live.dnsTimeouts} timed out" else "") +
                        " · ${live.tcpEstablished} TCP flows" +
                        " · ↓ ${formatBytes(live.rxBytes)} ↑ ${formatBytes(live.txBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "PPP in this image: ${if (pppProvisioned) "yes" else "no"} · " +
                    "NAT routing: ${if (natOn) "on" else "off"} · " +
                    "disk capacity: " +
                    (if (diskGiB > 0) "$diskGiB GiB" else "legacy (small)"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "Plain HTTP sites and repositories load. HTTPS generally does " +
                    "not: iPhone OS 3.1.3 speaks a TLS too old for today's servers " +
                    "(the TLS proxy is an upstream project, not yet built).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
