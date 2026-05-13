package com.delivery.motoboy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.delivery.motoboy.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import java.math.RoundingMode
import java.net.URI

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var socket: Socket? = null
    private var isOnline = false
    private var currentRequestId: String? = null
    private var currentDeliveryId: String? = null
    private var ringtone: MediaPlayer? = null
    private var serverUrl = DEFAULT_SERVER_URL

    // Mapa
    private var mapMarker: Marker? = null
    private var lastLat = 0.0
    private var lastLng = 0.0

    companion object {
        private const val DEFAULT_SERVER_URL = "https://delivery-tracker-6crw.onrender.com"
        private const val PERM_LOCATION = 1001
        private const val PERM_NOTIFICATION = 1002
        private const val PREFS = "motoboy_prefs"
        private const val KEY_SERVER_URL = "server_url"
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val deliveryId = currentDeliveryId ?: return

            lastLat = location.latitude
            lastLng = location.longitude

            socket?.emit("location-update-mobile", JSONObject().apply {
                put("deliveryId", deliveryId)
                put("lat", location.latitude)
                put("lng", location.longitude)
            })

            runOnUiThread {
                val lat = location.latitude.toBigDecimal().setScale(5, RoundingMode.HALF_UP)
                val lng = location.longitude.toBigDecimal().setScale(5, RoundingMode.HALF_UP)
                binding.tvGpsStatus.text = "📡 $lat, $lng"
                updateMap(location.latitude, location.longitude)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OSMDroid config
        Configuration.getInstance().userAgentValue = packageName

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        binding.etServerUrl.setText(serverUrl)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERM_NOTIFICATION
                )
            }
        }

        initMap()
        setupListeners()
        connectSocket()
    }

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

    private fun isGpsEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    private fun promptEnableGps() {
        AlertDialog.Builder(this)
            .setTitle("GPS desligado")
            .setMessage("O GPS precisa estar ativado para rastrear sua localização durante a entrega.")
            .setPositiveButton("Ativar GPS") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun connectSocket() {
        try {
            socket?.disconnect()
            val opts = IO.Options().apply {
                transports = arrayOf("websocket", "polling")
            }
            socket = IO.socket(URI.create(serverUrl), opts)

            socket?.on(Socket.EVENT_CONNECT) {
                runOnUiThread { binding.tvConnectionStatus.text = "● Conectado" }
            }
            socket?.on(Socket.EVENT_DISCONNECT) {
                runOnUiThread { binding.tvConnectionStatus.text = "○ Desconectado" }
            }
            socket?.on(Socket.EVENT_CONNECT_ERROR) {
                runOnUiThread { binding.tvConnectionStatus.text = "○ Erro de conexão" }
            }

            socket?.on("new-delivery-request") { args ->
                if (!isOnline) return@on
                val data = args.getOrNull(0) as? JSONObject ?: return@on
                runOnUiThread { showIncomingRequest(data) }
            }

            socket?.on("request-taken") { _ ->
                runOnUiThread {
                    if (currentRequestId != null) {
                        stopAlertSound()
                        hideRequestCard()
                        toast("Corrida aceita por outro motoboy")
                    }
                }
            }

            socket?.on("delivery-assigned") { args ->
                val data = args.getOrNull(0) as? JSONObject ?: return@on
                runOnUiThread {
                    onDeliveryAssigned(
                        data.getString("deliveryId"),
                        data.getString("trackingUrl")
                    )
                }
            }

            socket?.on("request-unavailable") { _ ->
                runOnUiThread {
                    stopAlertSound()
                    hideRequestCard()
                    toast("Corrida já foi aceita")
                }
            }

            socket?.connect()
        } catch (e: Exception) {
            binding.tvConnectionStatus.text = "○ Erro: ${e.message}"
        }
    }

    private fun setupListeners() {
        binding.btnSaveUrl.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                serverUrl = url
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVER_URL, url).apply()
                connectSocket()
                toast("Reconectando em $url")
            }
        }

        binding.btnToggleOnline.setOnClickListener { toggleOnline() }
        binding.btnAccept.setOnClickListener { acceptRequest() }
        binding.btnDecline.setOnClickListener {
            stopAlertSound()
            hideRequestCard()
        }
        binding.btnFinishDelivery.setOnClickListener { finishDelivery() }
        binding.btnOpenMaps.setOnClickListener { openGoogleMaps() }
    }

    private fun toggleOnline() {
        if (!isOnline && !isGpsEnabled()) {
            promptEnableGps()
            return
        }
        isOnline = !isOnline
        if (isOnline) {
            binding.ivStatusIcon.text = "🟢"
            binding.tvStatus.text = "Aguardando corridas..."
            binding.btnToggleOnline.text = "FICAR OFFLINE"
            binding.btnToggleOnline.setBackgroundColor(ContextCompat.getColor(this, R.color.red))
        } else {
            binding.ivStatusIcon.text = "⚫"
            binding.tvStatus.text = "Você está offline"
            binding.btnToggleOnline.text = "FICAR ONLINE"
            binding.btnToggleOnline.setBackgroundColor(ContextCompat.getColor(this, R.color.green))
            stopAlertSound()
            hideRequestCard()
        }
    }

    private fun showIncomingRequest(data: JSONObject) {
        currentRequestId = data.optString("requestId")
        binding.tvCustomerName.text = data.optString("customerName", "Cliente")
        binding.tvAddress.text = data.optString("address", "Sem endereço")
        val note = data.optString("note", "")
        binding.tvNote.text = note
        binding.tvNote.visibility = if (note.isNotEmpty()) View.VISIBLE else View.GONE
        binding.cardRequest.visibility = View.VISIBLE
        playAlertSound()
        vibrate()
    }

    private fun hideRequestCard() {
        binding.cardRequest.visibility = View.GONE
        currentRequestId = null
    }

    private fun acceptRequest() {
        if (!isGpsEnabled()) {
            promptEnableGps()
            return
        }
        val requestId = currentRequestId ?: return
        stopAlertSound()
        hideRequestCard()

        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        socket?.emit("accept-request", JSONObject().apply {
            put("requestId", requestId)
            put("motoboyId", deviceId)
        })
    }

    private fun onDeliveryAssigned(deliveryId: String, trackingUrl: String) {
        currentDeliveryId = deliveryId
        binding.tvDeliveryId.text = "Entrega #$deliveryId"
        binding.tvTrackingUrl.text = trackingUrl
        binding.tvGpsStatus.text = "📡 Iniciando GPS..."
        binding.tvStatus.text = "Entrega em andamento"
        binding.cardActiveDelivery.visibility = View.VISIBLE
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                PERM_LOCATION
            )
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun finishDelivery() {
        stopLocationUpdates()
        currentDeliveryId = null
        mapMarker = null
        binding.mapView.overlays.clear()
        binding.mapView.invalidate()
        binding.cardActiveDelivery.visibility = View.GONE
        binding.tvStatus.text = "Aguardando corridas..."
    }

    private fun openGoogleMaps() {
        if (lastLat == 0.0 && lastLng == 0.0) {
            toast("Aguardando localização GPS...")
            return
        }
        val uri = Uri.parse("google.navigation:q=$lastLat,$lastLng&mode=d")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            // Fallback: abre no browser
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://maps.google.com/?q=$lastLat,$lastLng")))
        }
    }

    private fun playAlertSound() {
        try {
            stopAlertSound()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = MediaPlayer.create(this, uri)
            ringtone?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            ringtone?.isLooping = true
            ringtone?.start()
        } catch (_: Exception) {}
    }

    private fun stopAlertSound() {
        try {
            ringtone?.stop()
            ringtone?.release()
            ringtone = null
        } catch (_: Exception) {}
    }

    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 150, 500, 150, 500), 0))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_LOCATION
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlertSound()
        stopLocationUpdates()
        socket?.disconnect()
    }
}
