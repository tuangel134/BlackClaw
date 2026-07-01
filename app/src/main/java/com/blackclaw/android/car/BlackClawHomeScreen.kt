package com.blackclaw.android.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import com.blackclaw.android.R

/**
 * Android Auto home: a driving-safe grid of one-tap actions. "Preguntar",
 * "Navegar", "Música" and "Llamar" open a voice search (the car transcribes
 * the speech); "Notificaciones" runs immediately and reads the result aloud.
 *
 * Every action funnels into the same task pipeline the phone uses, so anything
 * BlackClaw can do by voice on the phone also works here.
 */
class BlackClawHomeScreen(carContext: CarContext) : Screen(carContext) {

    private fun icon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()

    private fun gridItem(title: String, resId: Int, onClick: () -> Unit): GridItem =
        GridItem.Builder()
            .setTitle(title)
            .setImage(icon(resId), GridItem.IMAGE_TYPE_ICON)
            .setOnClickListener(onClick)
            .build()

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(gridItem("Preguntar", R.drawable.ic_car_mic) {
                screenManager.push(CarAskScreen(carContext, hint = "Pregúntame lo que sea", commandPrefix = ""))
            })
            .addItem(gridItem("Navegar", R.drawable.ic_car_navigation) {
                screenManager.push(CarAskScreen(carContext, hint = "¿A dónde vamos?", commandPrefix = "navégame a "))
            })
            .addItem(gridItem("Música", R.drawable.ic_car_music) {
                screenManager.push(CarAskScreen(carContext, hint = "¿Qué música pongo?", commandPrefix = "pon "))
            })
            .addItem(gridItem("Llamar", R.drawable.ic_car_call) {
                screenManager.push(CarAskScreen(carContext, hint = "¿A quién llamo?", commandPrefix = "llama a "))
            })
            .addItem(gridItem("Notificaciones", R.drawable.ic_car_bell) {
                screenManager.push(CarTaskScreen(carContext, "lee mis notificaciones sin abrir apps"))
            })
            .build()

        return GridTemplate.Builder()
            .setTitle("BlackClaw")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list)
            .build()
    }
}
