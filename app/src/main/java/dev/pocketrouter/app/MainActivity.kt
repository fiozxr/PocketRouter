package dev.pocketrouter.app

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

private val PocketColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF9BAAFF),
    secondary = androidx.compose.ui.graphics.Color(0xFF70D9C3),
    background = androidx.compose.ui.graphics.Color(0xFF10131A),
    surface = androidx.compose.ui.graphics.Color(0xFF191E28),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF252C39),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE8ECF5),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE8ECF5)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = PocketColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PocketRouterScreen(this)
                }
            }
        }
    }
}

private data class NetworkSnapshot(
    val networkName: String,
    val internet: String,
    val metered: String,
    val transports: String,
    val localAddresses: List<String>,
    val dnsServers: List<String>,
    val routes: List<String>,
    val note: String
)

private fun getNetworkSnapshot(context: Context): NetworkSnapshot {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val network = cm.activeNetwork
        ?: return NetworkSnapshot("No active network", "Unavailable", "Unknown", "None", emptyList(), emptyList(), emptyList(), "Connect to a network and refresh.")
    val caps = cm.getNetworkCapabilities(network)
    val links: LinkProperties? = cm.getLinkProperties(network)
    val transports = buildList {
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("Mobile data")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("Bluetooth")
    }.ifEmpty { listOf("Unknown") }
    val addresses = links?.linkAddresses.orEmpty().mapNotNull { link ->
        val address = link.address
        if (address.isLoopbackAddress || address.isLinkLocalAddress) null else "${address.hostAddress} (${if (address is Inet4Address) "IPv4" else "IPv6"})"
    }
    val dns = links?.dnsServers.orEmpty().map { it.hostAddress ?: it.toString() }
    val routes = links?.routes.orEmpty().take(8).map { route ->
        val gateway = route.gateway?.hostAddress ?: "on-link"
        "${route.destination} → $gateway"
    }
    val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    val networkName = when {
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi network"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Mobile network"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN network"
        else -> "Active network"
    }
    return NetworkSnapshot(
        networkName,
        when { validated -> "Internet reachable"; hasInternet -> "Internet not validated"; else -> "No internet capability" },
        if (metered) "Metered" else "Unmetered",
        transports.joinToString(" · "), addresses, dns, routes,
        "This describes PocketRouter's own active network. It does not prove the hotspot's downstream clients have internet access."
    )
}

@Composable
private fun PocketRouterScreen(activity: ComponentActivity) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(getNetworkSnapshot(activity)) }
    var host by remember { mutableStateOf("192.168.43.1") }
    var port by remember { mutableStateOf("8000") }
    var probeResult by remember { mutableStateOf("Enter a device IP and port, then tap Test port.") }
    var probing by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(false) }
    val discoveredServices = remember { mutableStateListOf<String>() }
    var discoveryMessage by remember { mutableStateOf("Discover HTTP services on the current LAN.") }
    val wifiManager = remember { activity.applicationContext.getSystemService(WifiManager::class.java) }
    var localHotspotReservation by remember { mutableStateOf<WifiManager.LocalOnlyHotspotReservation?>(null) }
    var localHotspotInfo by remember { mutableStateOf("Not running. This creates a separate local-only hotspot without internet sharing.") }
    var bridgeStatus by remember { mutableStateOf(PrivilegeBridge.shizukuStatus()) }
    var bridgeOutput by remember { mutableStateOf("Advanced diagnostics require Shizuku (started through ADB/Wireless debugging) or optional root access.") }
    var bridgeBusy by remember { mutableStateOf(false) }
    val nsdManager = remember { activity.getSystemService(Context.NSD_SERVICE) as NsdManager }
    var activeListener by remember { mutableStateOf<NsdManager.DiscoveryListener?>(null) }

    val startLocalHotspot: () -> Unit = {
        if (localHotspotReservation != null) {
            localHotspotInfo = "Local-only hotspot is already running."
        } else {
            localHotspotInfo = "Starting local-only hotspot…"
            try {
                wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        localHotspotReservation = reservation
                        localHotspotInfo = try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val config = reservation.softApConfiguration
                                "SSID: ${config.ssid}\nPassword: ${(config.passphrase?.ifBlank { "(none shown by device)" } ?: "(not exposed)")}\nLocal-only: no internet sharing"
                            } else {
                                @Suppress("DEPRECATION")
                                val config = reservation.wifiConfiguration
                                "SSID: ${config?.SSID ?: "(not exposed)"}\nPassword: ${config?.preSharedKey ?: "(not exposed)"}\nLocal-only: no internet sharing"
                            }
                        } catch (e: Exception) {
                            "Hotspot started, but Android did not expose its credentials: ${e.message}"
                        }
                    }
                    override fun onStopped() {
                        localHotspotReservation = null
                        localHotspotInfo = "Local-only hotspot stopped by Android."
                    }
                    override fun onFailed(reason: Int) {
                        localHotspotReservation = null
                        localHotspotInfo = "Could not start (reason $reason). The existing hotspot may conflict with local-only mode."
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (e: SecurityException) {
                localHotspotInfo = "Permission denied: allow Nearby devices and try again."
            } catch (e: Exception) {
                localHotspotInfo = "Could not start local-only hotspot: ${e.message}"
            }
        }
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLocalHotspot() else localHotspotInfo = "Nearby devices permission (Android 13+) or location permission (older Android) is required for local-only hotspot."
    }
    val lanPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            probeResult = "Nearby devices permission granted. Retry the port test."
            discoveryMessage = "Nearby devices permission granted. Start discovery when ready."
        } else {
            probeResult = "Permission denied. LAN checks may fail if Android local-network protection is enabled."
            discoveryMessage = "Permission denied. Service discovery may be restricted by Android."
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activeListener?.let { listener ->
                try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) { }
            }
            try { localHotspotReservation?.close() } catch (_: Exception) { }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("PocketRouter", fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Text("Local network toolkit · v0.2.0 · bridge edition", color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp)
            }
            OutlinedButton(onClick = { snapshot = getNetworkSnapshot(activity) }) { Text("Refresh") }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("NETWORK OVERVIEW", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(snapshot.networkName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                StatusLine("Internet", snapshot.internet)
                StatusLine("Metered status", snapshot.metered)
                StatusLine("Transport", snapshot.transports)
                Divider(color = MaterialTheme.colorScheme.surfaceVariant)
                Text("Local addresses", fontWeight = FontWeight.SemiBold)
                if (snapshot.localAddresses.isEmpty()) Text("No non-loopback address reported.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                snapshot.localAddresses.forEach { Text(it, fontSize = 13.sp) }
                Text("DNS: ${snapshot.dnsServers.ifEmpty { listOf("not reported") }.joinToString()}", fontSize = 12.sp)
                Text("Routes", fontWeight = FontWeight.SemiBold)
                snapshot.routes.forEach { Text(it, fontSize = 12.sp) }
                Text(snapshot.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PORT REACHABILITY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Text("Test a service on another device in your network.")
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, android.Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                    OutlinedButton(onClick = { lanPermissionLauncher.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant LAN / Nearby devices permission")
                    }
                }
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Device IP or hostname") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text("TCP port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !probing, onClick = {
                    val portNumber = port.toIntOrNull()
                    if (host.isBlank() || portNumber == null || portNumber !in 1..65535) {
                        probeResult = "Enter a valid host and a port from 1 to 65535."
                    } else {
                        probing = true
                        probeResult = "Testing…"
                        scope.launch {
                            probeResult = withContext(Dispatchers.IO) {
                                try {
                                    Socket().use { socket ->
                                        socket.connect(InetSocketAddress(host.trim(), portNumber), 1800)
                                    }
                                    "OPEN · TCP connection succeeded to $host:$portNumber"
                                } catch (e: Exception) {
                                    "UNREACHABLE · ${e.javaClass.simpleName}: ${e.message ?: "connection failed"}. Check the IP, server bind address, firewall and client isolation. If local-network protection is enabled, allow PocketRouter Nearby devices permission."
                                }
                            }
                            probing = false
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (probing) "Testing…" else "Test port") }
                Text(probeResult, fontSize = 13.sp)
                Text("This checks one TCP endpoint you specify. It does not scan the network automatically.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LOCAL SERVICE DISCOVERY", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Text("Look for services advertising HTTP over DNS-SD on the current network.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !discovering, onClick = {
                        discoveredServices.clear()
                        discovering = true
                        discoveryMessage = "Searching for _http._tcp services…"
                        val listener = object : NsdManager.DiscoveryListener {
                            override fun onDiscoveryStarted(serviceType: String) { discoveryMessage = "Discovery started. Results depend on services advertising themselves." }
                            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                                val item = "${serviceInfo.serviceName} · ${serviceInfo.serviceType}"
                                activity.runOnUiThread { if (item !in discoveredServices) discoveredServices.add(item) }
                                try {
                                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { }
                                        override fun onServiceResolved(info: NsdServiceInfo) {
                                            val endpoint = "${info.serviceName} · ${info.host?.hostAddress ?: "IP unknown"}:${info.port}"
                                            activity.runOnUiThread {
                                                val index = discoveredServices.indexOfFirst { it.startsWith(info.serviceName) }
                                                if (index >= 0) discoveredServices[index] = endpoint else discoveredServices.add(endpoint)
                                            }
                                        }
                                    })
                                } catch (_: Exception) { }
                            }
                            override fun onServiceLost(serviceInfo: NsdServiceInfo) { activity.runOnUiThread { discoveredServices.removeAll { it.startsWith(serviceInfo.serviceName) } } }
                            override fun onDiscoveryStopped(serviceType: String) { }
                            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                                activity.runOnUiThread { discovering = false; discoveryMessage = "Discovery failed (code $errorCode). Check Wi-Fi, Nearby devices permission and local-network restrictions." }
                                try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) { }
                            }
                            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { }
                        }
                        activeListener = listener
                        try { nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, listener) }
                        catch (e: Exception) { discovering = false; discoveryMessage = "Could not start discovery: ${e.message}" }
                    }) { Text("Discover") }
                    OutlinedButton(enabled = discovering, onClick = {
                        activeListener?.let { try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) { } }
                        activeListener = null
                        discovering = false
                        discoveryMessage = "Discovery stopped."
                    }) { Text("Stop") }
                }
                Text(discoveryMessage, fontSize = 13.sp)
                if (discoveredServices.isEmpty()) Text("No services found yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), fontSize = 13.sp)
                discoveredServices.forEach { Text("• $it", fontSize = 13.sp) }
                Text("Discovery only finds services that advertise using DNS-SD; it won't detect every open port or every server.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("METERED CONNECTION ASSISTANT", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Text("PocketRouter can report whether its own active network is marked metered. The setting that affects an app is usually on the connected client device.")
                OutlinedButton(onClick = {
                    try { activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                    catch (_: Exception) { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }, modifier = Modifier.fillMaxWidth()) { Text("Open Wi-Fi settings") }
                Text("On each client, open the hotspot's Wi-Fi details and look for 'Treat as metered' or 'Network usage'. Choose unmetered if your Android version offers it. Some apps enforce their own limits.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LOCAL-ONLY HOTSPOT LAB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Text("Create a separate Wi-Fi network for local device-to-device experiments. Android does not share mobile internet through this mode.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = localHotspotReservation == null, onClick = {
                        val permission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.NEARBY_WIFI_DEVICES else android.Manifest.permission.ACCESS_FINE_LOCATION
                        if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                            nearbyPermissionLauncher.launch(permission)
                        } else startLocalHotspot()
                    }) { Text("Start local network") }
                    OutlinedButton(enabled = localHotspotReservation != null, onClick = {
                        try { localHotspotReservation?.close() } catch (_: Exception) { }
                        localHotspotReservation = null
                        localHotspotInfo = "Local-only hotspot stopped."
                    }) { Text("Stop") }
                }
                Text(localHotspotInfo, fontSize = 13.sp)
                Text("This may fail while the normal mobile hotspot is active. It does not replace or reconfigure your existing tethered hotspot.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ADVANCED ACCESS BRIDGE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                Text("Advanced diagnostics use Shizuku started through ADB/Wireless debugging, or optional root access. Basic network diagnostics remain available without either. Shizuku permission is granted by you in the Shizuku prompt; root is requested only when you tap the root test.")
                Text(bridgeStatus, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        bridgeStatus = PrivilegeBridge.shizukuStatus()
                        if (PrivilegeBridge.shizukuBinderAvailable() && !PrivilegeBridge.shizukuPermissionGranted()) {
                            try {
                                PrivilegeBridge.requestShizukuPermission()
                                bridgeStatus = "Permission request sent. Approve PocketRouter in the Shizuku dialog, then refresh status."
                            } catch (e: Exception) { bridgeStatus = "Could not request Shizuku permission: ${e.message}" }
                        }
                    }) { Text("Connect Shizuku") }
                    OutlinedButton(onClick = { bridgeStatus = PrivilegeBridge.shizukuStatus() }) { Text("Refresh status") }
                }
                Button(enabled = !bridgeBusy, onClick = {
                    bridgeBusy = true
                    bridgeOutput = "Running read-only diagnostics through Shizuku…"
                    scope.launch {
                        bridgeOutput = withContext(Dispatchers.IO) {
                            try { PrivilegeBridge.runShizukuIdentity() }
                            catch (e: Exception) { "Shizuku diagnostics unavailable: ${e.message ?: e.javaClass.simpleName}" }
                        }
                        bridgeBusy = false
                        bridgeStatus = PrivilegeBridge.shizukuStatus()
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (bridgeBusy) "Running…" else "Run Shizuku network diagnostics") }
                OutlinedButton(enabled = !bridgeBusy, onClick = {
                    bridgeBusy = true
                    bridgeOutput = "Requesting optional root access for read-only diagnostics… approve the superuser prompt if one appears."
                    scope.launch {
                        bridgeOutput = withContext(Dispatchers.IO) {
                            try { PrivilegeBridge.runRootIdentity() }
                            catch (e: Exception) { "Root diagnostics unavailable: ${e.message ?: e.javaClass.simpleName}. Root is optional; this does not root your phone." }
                        }
                        bridgeBusy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Test optional root access") }
                Text("Diagnostics output", fontWeight = FontWeight.SemiBold)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Text(bridgeOutput, modifier = Modifier.fillMaxWidth().padding(12.dp), fontSize = 12.sp)
                }
                Text("Read-only commands inspect identity, Android version, routes, neighbor cache and tethering diagnostics. This version does not apply firewall/NAT changes. Shizuku is an ADB-backed bridge, not an ADB server embedded in PocketRouter.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CURRENT LIMITATIONS", fontWeight = FontWeight.Bold)
                Text("• Advanced diagnostics use Shizuku/ADB or optional root; ordinary LAN checks work without them.\n• System hotspot DHCP, gateway, firewall, NAT and client-isolation changes are not implemented and may require root or privileged system APIs.\n• Wi-Fi Direct/proxy internet-sharing mode remains planned.\n• Local-only hotspot mode creates a separate no-internet network and may conflict with the existing hotspot.\n• Root-only extra controls are not enabled until their commands are implemented and device-tested.", fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}
