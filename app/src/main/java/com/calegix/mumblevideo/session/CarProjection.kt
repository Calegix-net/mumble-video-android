package com.calegix.mumblevideo.session

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * True while Android Auto shows this phone on a car's screen, wired or wireless. Android Automotive
 * (the car running Android itself) is not a phone in a car, so it doesn't count.
 */
fun carProjection(context: Context): Flow<Boolean> = callbackFlow {
    val type = CarConnection(context.applicationContext).type
    val observer = Observer<Int> { trySend(it == CarConnection.CONNECTION_TYPE_PROJECTION) }
    type.observeForever(observer)
    awaitClose { type.removeObserver(observer) }
}.flowOn(Dispatchers.Main.immediate).distinctUntilChanged()
