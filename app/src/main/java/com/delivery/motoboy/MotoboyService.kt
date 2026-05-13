package com.delivery.motoboy

import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.*
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI

class MotoboyService : Service() {

    private var socket: Socket? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentDeliveryId: String? = null
    private var currentRequestId: String? = null
    private var ringtone: MediaPlayer? = null
    private var serverUrl = "https://delivery-tracker-6crw.onrender.com"

    companion object {
        const val ACTION_ACCEPT  = "com.delivery.motoboy.ACCEPT"
        const val ACTION_DECLINE = "com.delivery.motoboy.DECLINE"
        const val ACTION_FINISH  = "com.delivery.motoboy.FINISH"

        const val BROADCAST_NEW_REQUEST        = "com.delivery.motoboy.NEW_REQUEST"
        const val BROADCAST_REQUEST_TAKEN      = "com.delivery.motoboy.REQUEST_TAKEN"
        const val BROADCAST_DELIVERY_ASSIGNED  = "com.delivery.motoboy.DELIVERY_ASSIGNED"
        const val BROADCAST_REQUEST_UNAVAILABLE= "com.delivery.motoboy.REQUEST_UNAVAILABLE"
        const val BROADCAST_CONNECTED          = "com.delivery.motoboy.CONNECTED"
        const val BROADCAST_DISCONNECTED       = "com.delivery.motoboy.DISCONNECTED"
        const val BROADCAST_LOCATION           = "com.delivery.motoboy.LOCATION"
        const val BROADCAST_DELIVERY_FINISHED  = "com.delivery.motoboy.DELIVERY_FINISHED"

        const val EXTRA_REQUEST_JSON  = "request_json"
        const val EXTRA_DELIVERY_ID   = "delivery_id"
        const val EXTRA_TRACKING_URL  = "tracking_url"
        const val EXTRA_REQUEST_ID    = "request_id"
        const val EXTRA_SERVER_URL    = "server_url"

        const val CHANNEL_SERVICE  = "ch_service"
        const val CHANNEL_REQUESTS = "ch_requests"
        const val NOTIF_SERVICE    = 1
        const val NOTIF_REQUEST    = 2
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            val id  = currentDeliveryId ?: return
            socket?.emit("location-update-mobile", JSONObject().apply {
                put("deliveryId", id)
                put("lat", loc.latitude)
                put("lng", loc.longitude)
            })
            sendBroadcast(Intent(BROADCAST_LOCATION).apply {
                putExtra("lat", loc.latitude)
                putExtra("lng", loc.longitude)
            })
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        setupChannels()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACCEPT -> acceptRequest(intent.getStringExtra(EXTRA_REQUEST_ID) ?: "")
            ACTION_DECLINE -> {
                stopAlertSound()
                NotificationManagerCompat.from(this).cancel(NOTIF_REQUEST)
                sendBroadcast(Intent(BROADCAST_REQUEST_TAKEN))
            }
            ACTION_FINISH -> finishDelivery()
            else -> {
                serverUrl = intent?.getStringExtra(EXTRA_SERVER_URL) ?: serverUrl
                startForegroundCompat()
                connectSocket()
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notif = buildServiceNotif("🟢 Online — aguardando corridas...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_SERVICE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_SERVICE, notif)
        }
    }

    private fun connectSocket() {
        try {
            socket?.disconnect()
            socket = IO.socket(URI.create(serverUrl), IO.Options().apply {
                transports = arrayOf("websocket", "polling")
            })

            socket?.on(Socket.EVENT_CONNECT) {
                sendBroadcast(Intent(BROADCAST_CONNECTED))
                updateServiceNotif("🟢 Online — aguardando corridas...")
            }
            socket?.on(Socket.EVENT_DISCONNECT) {
                sendBroadcast(Intent(BROADCAST_DISCONNECTED))
                updateServiceNotif("⚠️ Reconectando...")
            }

            socket?.on("new-delivery-request") { args ->
                val data = args.getOrNull(0) as? JSONObject ?: return@on
                currentRequestId = data.optString("requestId")
                sendBroadcast(Intent(BROADCAST_NEW_REQUEST).apply {
                    putExtra(EXTRA_REQUEST_JSON, data.toString())
                })
                showRequestNotif(
                    data.optString("customerName", "Cliente"),
                    data.optString("address", ""),
                    currentRequestId ?: ""
                )
                playAlertSound()
                vibrate()
            }

            socket?.on("request-taken") { _ ->
                stopAlertSound()
                NotificationManagerCompat.from(this).cancel(NOTIF_REQUEST)
                sendBroadcast(Intent(BROADCAST_REQUEST_TAKEN))
            }

            socket?.on("delivery-assigned") { args ->
                val data = args.getOrNull(0) as? JSONObject ?: return@on
                stopAlertSound()
                NotificationManagerCompat.from(this).cancel(NOTIF_REQUEST)
                val deliveryId   = data.getString("deliveryId")
                val trackingUrl  = data.getString("trackingUrl")
                currentDeliveryId = deliveryId
                sendBroadcast(Intent(BROADCAST_DELIVERY_ASSIGNED).apply {
                    putExtra(EXTRA_DELIVERY_ID, deliveryId)
                    putExtra(EXTRA_TRACKING_URL, trackingUrl)
                })
                updateServiceNotif("🚴 Entrega #$deliveryId em andamento")
                startLocationUpdates()
            }

            socket?.on("request-unavailable") { _ ->
                stopAlertSound()
                NotificationManagerCompat.from(this).cancel(NOTIF_REQUEST)
                sendBroadcast(Intent(BROADCAST_REQUEST_UNAVAILABLE))
            }

            socket?.connect()
        } catch (_: Exception) {}
    }

    private fun acceptRequest(requestId: String) {
        stopAlertSound()
        NotificationManagerCompat.from(this).cancel(NOTIF_REQUEST)
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        socket?.emit("accept-request", JSONObject().apply {
            put("requestId", requestId)
            put("motoboyId", deviceId)
        })
    }

    private fun finishDelivery() {
        stopLocationUpdates()
        currentDeliveryId = null
        updateServiceNotif("🟢 Online — aguardando corridas...")
        sendBroadcast(Intent(BROADCAST_DELIVERY_FINISHED))
    }

    private fun startLocationUpdates() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
            .setMinUpdateIntervalMillis(2000).build()
        fusedLocationClient.requestLocationUpdates(req, locationCallback, mainLooper)
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // ── Notificações ─────────────────────────────────────────────────────────

    private fun setupChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_SERVICE, "Status do motoboy", NotificationManager.IMPORTANCE_LOW
            ))
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_REQUESTS, "Novas corridas", NotificationManager.IMPORTANCE_HIGH
            ).apply { enableVibration(true); enableLights(true) })
        }
    }

    private fun mainPendingIntent() = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildServiceNotif(text: String) =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("MotoApp")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(mainPendingIntent())
            .setOngoing(true)
            .build()

    private fun updateServiceNotif(text: String) =
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_SERVICE, buildServiceNotif(text))

    private fun showRequestNotif(customerName: String, address: String, requestId: String) {
        val acceptPi = PendingIntent.getService(
            this, 1,
            Intent(this, MotoboyService::class.java).apply {
                action = ACTION_ACCEPT
                putExtra(EXTRA_REQUEST_ID, requestId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val declinePi = PendingIntent.getService(
            this, 2,
            Intent(this, MotoboyService::class.java).apply { action = ACTION_DECLINE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_REQUESTS)
            .setContentTitle("🔔 Nova corrida!")
            .setContentText("$customerName — $address")
            .setStyle(NotificationCompat.BigTextStyle().bigText("👤 $customerName\n📍 $address"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(mainPendingIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Recusar", declinePi)
            .addAction(android.R.drawable.ic_menu_send, "✓ Aceitar", acceptPi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(mainPendingIntent(), true)
            .setAutoCancel(false)
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_REQUEST, notif)
    }

    // ── Som / Vibração ────────────────────────────────────────────────────────

    private fun playAlertSound() {
        try {
            ringtone?.release()
            ringtone = MediaPlayer.create(this,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ringtone?.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).build())
            ringtone?.isLooping = true
            ringtone?.start()
        } catch (_: Exception) {}
    }

    private fun stopAlertSound() {
        try { ringtone?.stop(); ringtone?.release(); ringtone = null } catch (_: Exception) {}
    }

    private fun vibrate() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 150, 500, 150, 500), 0))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlertSound()
        stopLocationUpdates()
        socket?.disconnect()
    }
}
