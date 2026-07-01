package com.blackclaw.android.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

/**
 * Voice/text search screen. On Android Auto the car provides its own
 * speech-to-text for this template, so the user speaks the destination / song /
 * contact / question hands-free. The transcribed text is combined with
 * [commandPrefix] and executed as a normal BlackClaw task.
 */
class CarAskScreen(
    carContext: CarContext,
    private val hint: String,
    private val commandPrefix: String,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) { /* live results not needed */ }

            override fun onSearchSubmitted(searchText: String) {
                val query = searchText.trim()
                if (query.isEmpty()) return
                val command = (commandPrefix + query).trim()
                screenManager.push(CarTaskScreen(carContext, command))
            }
        }

        return SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint(hint)
            .setShowKeyboardByDefault(false)
            .build()
    }
}
