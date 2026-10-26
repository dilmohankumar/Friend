package com.situationship.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.situationship.app.call.CallManager
import com.situationship.app.data.model.UserStatus
import com.situationship.app.data.repo.ChatRepository
import com.situationship.app.data.repo.SessionRepository
import com.situationship.app.data.socket.SocketManager
import com.situationship.app.push.Notifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SituationshipApp : Application() {
    @Inject lateinit var socket: SocketManager
    @Inject lateinit var session: SessionRepository
    @Inject lateinit var calls: CallManager
    @Inject lateinit var chat: ChatRepository // starts its realtime collectors

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)

        // Socket only while the app is visible (or a call is running). FCM covers the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (session.user.value?.status == UserStatus.ACTIVE) socket.connect()
            }
            override fun onStop(owner: LifecycleOwner) {
                if (!calls.state.value.active) socket.disconnect()
            }
        })
    }
}
