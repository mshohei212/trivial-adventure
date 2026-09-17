package com.GoogleLLC.TrivialAdventure

import android.app.Application
import com.GoogleLLC.TrivialAdventure.game.GameProgressStore
import com.GoogleLLC.TrivialAdventure.trials.GameTrialsManager

/**
 * Manual DI container. A sandbox this size does not justify a DI framework, and keeping the
 * graph explicit makes it obvious that exactly one [GameTrialsManager] exists per process
 * (the SDK binding is resolved once and cached).
 */
class TrivialAdventureApp : Application() {

    val trialsManager: GameTrialsManager by lazy { GameTrialsManager(this) }

    val progressStore: GameProgressStore by lazy { GameProgressStore(this) }
}
