package com.app.openstreetmapapp.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.app.openstreetmapapp.models.LatLong
import com.app.openstreetmapapp.vm.MapViewModel
import com.app.openstreetmapapp.R
import com.app.openstreetmapapp.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.URL
import kotlin.math.*
import androidx.core.graphics.createBitmap

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: MapView
    private lateinit var symbolManager: SymbolManager
    private lateinit var lineManager: LineManager
    private lateinit var carSymbol: Symbol
    private lateinit var viewModel: MapViewModel

    private var snappedRoute: List<Point> = emptyList()
    private var routeLine: Line? = null
    private var animationJob: Job? = null

    private val speedMetersPerSec = 60.0f

    lateinit var binding:ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        viewModel = ViewModelProvider(this).get(MapViewModel::class.java)

        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapLibreMap ->
            mapLibreMap.setStyle("https://tiles.openfreemap.org/styles/liberty") { style ->
                val carIconDrawable = ContextCompat.getDrawable(this, R.drawable.car_icon_24)
                val carIconBitmap = carIconDrawable?.let { drawableToBitmap(it) }
                carIconBitmap?.let { style.addImage("car-icon", it, true) }

                setupLineManager(mapLibreMap, style)
                setupSymbolManager(mapLibreMap, style)

                drawRoutePath()
            }
        }
    }

    // method for adding symbols and other things on map
    private fun setupSymbolManager(mapLibreMap: org.maplibre.android.maps.MapLibreMap, style: Style) {
        symbolManager = SymbolManager(mapView, mapLibreMap, style)
        symbolManager.iconAllowOverlap = true
        symbolManager.textAllowOverlap = true
    }

    // method for showing lines and routes on map which depicts paths
    private fun setupLineManager(mapLibreMap: org.maplibre.android.maps.MapLibreMap, style: Style) {
        lineManager = LineManager(mapView, mapLibreMap, style)
    }

    // method for downloading coordinates and collecting a list of route points
    private fun drawRoutePath() {
        val points = viewModel.getRoutePoints()
        if (points.size < 2) return

        val start = points.first()
        val end = points.last()

        val url = "https://router.project-osrm.org/route/v1/driving/${start.lon},${start.lat};${end.lon},${end.lat}?overview=full&geometries=geojson"

        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { URL(url).readText() }
                val json = JSONObject(response)
                val routes = json.getJSONArray("routes")
                if (routes.length() > 0) {
                    val geometry = routes.getJSONObject(0).getJSONObject("geometry")
                    val coords = geometry.getJSONArray("coordinates")

                    val routePoints = mutableListOf<Point>()
                    for (i in 0 until coords.length()) {
                        val coord = coords.getJSONArray(i)
                        routePoints.add(Point.fromLngLat(coord.getDouble(0), coord.getDouble(1)))
                    }

                    snappedRoute = routePoints

                    val lineString = LineString.fromLngLats(routePoints)
                    routeLine = lineManager.create(
                        LineOptions()
                            .withGeometry(lineString)
                            .withLineWidth(4.0f)
                            .withLineColor("#FF0000")
                    )

                    startCarAnimation()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // method for start moving the car on route and updating route
    private fun startCarAnimation() {
        animationJob?.cancel()
        if (snappedRoute.isEmpty()) return

        animationJob = lifecycleScope.launch {
            val start = snappedRoute.first()
            if (!::carSymbol.isInitialized) {
                carSymbol = symbolManager.create(
                    SymbolOptions()
                        .withLatLng(LatLng(start.latitude(), start.longitude()))
                        .withIconImage("car-icon")
                        .withIconSize(1.2f)
                        .withIconRotate(0.0f)
                )
            } else {
                carSymbol.setLatLng(LatLng(start.latitude(), start.longitude()))
                carSymbol.setIconRotate(0.0f)
                symbolManager.update(carSymbol)
            }

            centerCamera(LatLong(start.latitude(), start.longitude()))

            for (i in 1 until snappedRoute.size) {
                val a = snappedRoute[i - 1]
                val b = snappedRoute[i]

                val segmentDist =
                    shortestDisranceBtwPoints(a.latitude(), a.longitude(), b.latitude(), b.longitude())
                if (segmentDist < 0.5) continue

                val segmentDurationMs = ((segmentDist / speedMetersPerSec) * 1000.0).toLong().coerceAtLeast(50L)
                val fps = 30
                val steps = max(1, (segmentDurationMs * fps / 1000).toInt())
                val bearing = checkCarFace(a.latitude(), a.longitude(), b.latitude(), b.longitude())

                for (step in 1..steps) {
                    val t = step.toDouble() / steps.toDouble()
                    val lat = lerp(a.latitude(), b.latitude(), t)
                    val lon = lerp(a.longitude(), b.longitude(), t)

                    carSymbol.setLatLng(LatLng(lat, lon))
                    carSymbol.setIconRotate(bearing.toFloat())
                    symbolManager.update(carSymbol)

                    centerCamera(LatLong(lat, lon))

                    routeLine?.let { line ->
                        val remainingPoints = snappedRoute.drop(i)
                        if (remainingPoints.isNotEmpty()) {
                            line.geometry = LineString.fromLngLats(remainingPoints)
                            lineManager.update(line)
                        }
                    }

                    delay((segmentDurationMs / steps.toDouble()).toLong())
                }
            }
        }
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    // method for finding the shortest path between two points
    private fun shortestDisranceBtwPoints(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)
        val a =
            sin(dLat / 2).pow(2.0) + cos(radLat1) * cos(radLat2) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // method for rotating car face according to direction
    private fun checkCarFace(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val lambda1 = Math.toRadians(lon1)
        val lambda2 = Math.toRadians(lon2)
        val y = sin(lambda2 - lambda1) * cos(phi2)
        val x =
            cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(lambda2 - lambda1)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun centerCamera(position: LatLong) {
        val cameraPosition = CameraPosition.Builder()
            .target(LatLng(position.lat, position.lon))
            .zoom(16.0)
            .build()
        mapView.getMapAsync { mapLibreMap ->
            mapLibreMap.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        }
    }

    // this method is to create bitmap of car image to show on map
    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        val bitmap = createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onStart() {
        super.onStart(); mapView.onStart()
    }

    override fun onResume() {
        super.onResume(); mapView.onResume()
    }

    override fun onPause() {
        super.onPause(); mapView.onPause()
    }

    override fun onStop() {
        super.onStop(); mapView.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory(); mapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
        animationJob?.cancel()
    }
}

