package com.example.gpsapp

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MainActivity : AppCompatActivity() {

    private val LOCATION_PERMISSION_REQUEST_CODE = 100
    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener
    private lateinit var locationTextView: TextView

    private lateinit var locationEditText: EditText

    private lateinit var getLocationButton: Button
    private lateinit var sendLocationButton: Button
    private var currentLocation: Location? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        locationEditText = findViewById(R.id.locationEditText)
        locationTextView = findViewById(R.id.locationTextView)
        getLocationButton = findViewById(R.id.getLocationButton)
        sendLocationButton = findViewById(R.id.sendLocationButton)

        // Itt állítjuk be az EditText alapértelmezett értékét
        // Most már csak az alapcímet tartalmazza, a "/gps" nélkül
        locationEditText.setText("http://192.168.1.105:5000")

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLocation = location
                val latitude = location.latitude
                val longitude = location.longitude
                val timestamp = Date(location.time).toString()
                locationTextView.text = "Szélesség: $latitude\nHosszúság: $longitude\nIdőbélyeg: $timestamp"
                sendLocationButton.isEnabled = true
            }

            override fun onProviderDisabled(provider: String) {
                Toast.makeText(this@MainActivity, "$provider ki van kapcsolva!", Toast.LENGTH_SHORT).show()
                sendLocationButton.isEnabled = false
            }

            override fun onProviderEnabled(provider: String) {
                Toast.makeText(this@MainActivity, "$provider be van kapcsolva!", Toast.LENGTH_SHORT).show()
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }

        getLocationButton.setOnClickListener {
            checkLocationPermissionAndRequestUpdates()
        }

        sendLocationButton.setOnClickListener {
            currentLocation?.let { location ->
                sendLocationToServer(location.latitude, location.longitude, location.time)
            } ?: run {
                Toast.makeText(this@MainActivity, "Nincs elérhető helyadat!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkLocationPermissionAndRequestUpdates() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestLocationUpdates()
            } else {
                Toast.makeText(this, "Helymeghatározási engedély megtagadva!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestLocationUpdates() {
        try {
            val isGPSEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGPSEnabled && !isNetworkEnabled) {
                Toast.makeText(this, "Kapcsold be a GPS-t vagy a hálózati helymeghatározást!", Toast.LENGTH_SHORT).show()
                return
            }

            if (isGPSEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000, // Frissítési időköz milliszekundumban
                    1f,   // Minimális elmozdulás méterben
                    locationListener
                )
            } else if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000,
                    1f,
                    locationListener
                )
            }
        } catch (securityException: SecurityException) {
            Toast.makeText(this, "Helymeghatározási engedély szükséges!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendLocationToServer(latitude: Double, longitude: Double, timestamp: Long) {
        // Lekérjük az alap URL-t az EditText-ből
        val baseUrl = locationEditText.text.toString()

        // Ellenőrizzük, hogy az alap URL nem üres-e
        if (baseUrl.isBlank()) {
            GlobalScope.launch(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Kérlek, add meg a szerver alap URL-jét!", Toast.LENGTH_LONG).show()
            }
            return // Kilépés a függvényből, ha az URL üres
        }

        // Itt fűzzük hozzá a fix "/gps" útvonalat
        val serverUrl = "$baseUrl/gps"

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val locationData = mapOf(
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "timestamp" to timestamp
                )
                val json = Gson().toJson(locationData)
                val requestBody = json.toRequestBody(mediaTypeJson)
                val request = Request.Builder()
                    .url(serverUrl) // Itt már a teljes URL-t használjuk
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()

                launch(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@MainActivity, "Helyzet sikeresen elküldve!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Hiba a küldés során: ${response.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                response.close()
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Hiba a hálózati kérés során: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
                e.printStackTrace()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        locationManager.removeUpdates(locationListener)
    }
}
