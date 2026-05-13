package com.delivery.motoboy

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.delivery.motoboy.databinding.ActivityMainBinding
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isOnline = false
    private var currentRequestId: String? = null
    private var currentDeliveryId: String? = null
    private var mapMarker: Marker? = null
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var serverUrl = DEFAULT_SERVER_URL

    companion object {
        private const val DEFAULT_SERVER_URL = "https://delivery-tracker-6crw.onrender.com"
        private const val PERM_LOCATION = 1001
        private const val PERM_NOTIFICATION = 1002
        private const val PREFS = "motoboy_prefs"
        private const val KEY_SERVER_URL = "server_url"
    }

    // ── BroadcastReceiver ─────────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MotoboyService.BROADCAST_CONNECTED    -> binding.tvConnectionStatus.text = "● Conectado"
                MotoboyService.BROADCAST_DISCONNECTED -> binding.tvConnectionStatus.text = "○ Desconectado"

                MotoboyService.BROADCAST_NEW_REQUEST -> {
                    val json = intent.getStringExtra(MotoboyService.EXTRA_REQUEST_JSON) ?: return
                    showIncomingRequest(JSONObject(json))
                }

                MotoboyService.BROADCAST_REQUEST_TAKEN -> {
                    hideRequestCard()
                    toast("Corrida aceita por outro motoboy")
                }

                MotoboyService.BROADCAST_DELIVERY_ASSIGNED -> {
                    val deliveryId  = intent.getStringExtra(MotoboyService.EXTRA_DELIVERY_ID) ?: return
                    val trackingUrl = intent.getStringExtra(MotoboyService.EXTRA_TRACKING_URL) ?: ""
                    onDeliveryAssigned(deliveryId, trackingUrl)
                }

                MotoboyService.BROADCAST_REQUEST_UNAVAILABLE -> {
                    hideRequestCard()
                    toast("Corrida já foi aceita")
                }

                MotoboyService.BROADCAST_LOCATION -> {
                    val lat = intent.getDoubleExtra("lat", 0.0)
                    val lng = intent.getDoubleExtra("lng", 0.0)
                    lastLat = lat; lastLng = lng
                    binding.tvGpsStatus.text = "📡 ${"%.5f".format(lat)}, ${"%.5f".format(lng)}"
                    updateMap(lat, lng)
                }

                MotoboyService.BROADCAST_DELIVERY_FINISHED -> {
                    currentDeliveryId = null
                    binding.cardActiveDelivery.visibility = View.GONE
                    binding.tvStatus.text = "Aguardando corridas..."
                    clearMap()
                }
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        binding.etServerUrl.setText(serverUrl)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERM_NOTIFICATION)
            }
        }

        initMap()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        val filter = IntentFilter().apply {
            addAction(MotoboyService.BROADCAST_CONNECTED)
            addAction(MotoboyService.BROADCAST_DISCONNECTED)
            addAction(MotoboyService.BROADCAST_NEW_REQUEST)
            addAction(MotoboyService.BROADCAST_REQUEST_TAKEN)
            addAction(MotoboyService.BROADCAST_DELIVERY_ASSIGNED)
            addAction(MotoboyService.BROADCAST_REQUEST_UNAVAILABLE)
            addAction(MotoboyService.BROADCAST_LOCATION)
            addAction(MotoboyService.BROADCAST_DELIVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    // ── Mapa ──────────────────────────────────────────────────────────────────

    private fun initMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }
    }

    private fun updateMap(lat: Double, lng: Double) {
        val point = GeoPoint(lat, lng)
        if (mapMarker == null) {
            mapMarker = Marker(binding.mapView).apply {
                title = "Você está aqui"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            binding.mapView.overlays.add(mapMarker)
        }
        mapMarker?.position = point
        binding.mapView.controller.animateTo(point)
        binding.mapView.invalidate()
    }

    private fun clearMap() {
        mapMarker = null
        binding.mapView.overlays.clear()
        binding.mapView.invalidate()
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnSaveUrl.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                serverUrl = url
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_SERVER_URL, url).apply()
                if (isOnline) {
                    stopService()
                    startService()
                }
                toast("Servidor atualizado")
            }
        }

        binding.btnToggleOnline.setOnClickListener { toggleOnline() }

        binding.btnAccept.setOnClickListener { acceptRequest() }

        binding.btnDecline.setOnClickListener {
            startService(MotoboyService.ACTION_DECLINE)
            hideRequestCard()
        }

        binding.btnFinishDelivery.setOnClickListener {
            startService(MotoboyService.ACTION_FINISH)
        }

        binding.btnOpenMaps.setOnClickListener { openGoogleMaps() }
    }

    // ── Online / Offline ──────────────────────────────────────────────────────

    private fun toggleOnline() {
        if (!isOnline && !isGpsEnabled()) { promptEnableGps(); return }

        if (!isOnline && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERM_LOCATION)
            return
        }

        isOnline = !isOnline
        if (isOnline) {
            startService()
            binding.ivStatusIcon.text = "🟢"
            binding.tvStatus.text = "Aguardando corridas..."
            binding.btnToggleOnline.text = "FICAR OFFLINE"
            binding.btnToggleOnline.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
            binding.tvConnectionStatus.text = "○ Conectando..."
        } else {
            stopService()
            binding.ivStatusIcon.text = "⚫"
            binding.tvStatus.text = "Você está offline"
            binding.btnToggleOnline.text = "FICAR ONLINE"
            binding.btnToggleOnline.setBackgroundColor(ContextCompat.getColor(this, R.color.green))
            binding.tvConnectionStatus.text = "○ Desconectado"
            hideRequestCard()
        }
    }

    private fun startService(action: String? = null) {
        val intent = Intent(this, MotoboyService::class.java).apply {
            this.action = action
            putExtra(MotoboyService.EXTRA_SERVER_URL, serverUrl)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
    }

    private fun stopService() {
        stopService(Intent(this, MotoboyService::class.java))
    }

    // ── Corridas ──────────────────────────────────────────────────────────────

    private fun showIncomingRequest(data: JSONObject) {
        currentRequestId = data.optString("requestId")
        binding.tvCustomerName.text = data.optString("customerName", "Cliente")
        binding.tvAddress.text = data.optString("address", "Sem endereço")
        val note = data.optString("note", "")
        binding.tvNote.text = note
        binding.tvNote.visibility = if (note.isNotEmpty()) View.VISIBLE else View.GONE
        binding.cardRequest.visibility = View.VISIBLE
    }

    private fun hideRequestCard() {
        binding.cardRequest.visibility = View.GONE
        currentRequestId = null
    }

    private fun acceptRequest() {
        if (!isGpsEnabled()) { promptEnableGps(); return }
        val requestId = currentRequestId ?: return
        hideRequestCard()
        startService(Intent(this, MotoboyService::class.java).apply {
            action = MotoboyService.ACTION_ACCEPT
            putExtra(MotoboyService.EXTRA_REQUEST_ID, requestId)
        })
    }

    private fun onDeliveryAssigned(deliveryId: String, trackingUrl: String) {
        currentDeliveryId = deliveryId
        binding.tvDeliveryId.text = "Entrega #$deliveryId"
        binding.tvTrackingUrl.text = trackingUrl
        binding.tvGpsStatus.text = "📡 Iniciando GPS..."
        binding.tvStatus.text = "Entrega em andamento"
        binding.cardActiveDelivery.visibility = View.VISIBLE
    }

    private fun openGoogleMaps() {
        if (lastLat == 0.0) { toast("Aguardando GPS..."); return }
        val uri = Uri.parse("google.navigation:q=$lastLat,$lastLng&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
        else startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("https://maps.google.com/?q=$lastLat,$lastLng")))
    }

    // ── GPS ───────────────────────────────────────────────────────────────────

    private fun isGpsEnabled() =
        (getSystemService(LOCATION_SERVICE) as LocationManager)
            .isProviderEnabled(LocationManager.GPS_PROVIDER)

    private fun promptEnableGps() {
        AlertDialog.Builder(this)
            .setTitle("GPS desligado")
            .setMessage("Ative o GPS para rastrear sua localização durante a entrega.")
            .setPositiveButton("Ativar GPS") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_LOCATION
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleOnline()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
