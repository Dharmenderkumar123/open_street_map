package com.app.openstreetmapapp.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.openstreetmapapp.routeInfo.DemoRoute
import com.app.openstreetmapapp.models.LatLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

class MapViewModel : ViewModel() {

    private val _current = MutableStateFlow<LatLong?>(null)

    private val route = DemoRoute.points

    init {
        startSimulation()
    }


    // method contains for loop which has 2-3 second interval to iterate the route list
    private fun startSimulation() {
        viewModelScope.launch {
            if (route.isNotEmpty()) {
                _current.value = route[0]
            }

            var i = 0
            for (step in 0 until Int.MAX_VALUE) {
                val waitMs = 2000 + Random.nextLong(0, 1000)
                delay(waitMs)

                i++
                if (i >= route.size) {
                    i = 0
                }

                _current.value = route[i]
            }
        }
    }

    //    method to get the route points on UI layer
    fun getRoutePoints(): List<LatLong> {
        return route
    }
}
