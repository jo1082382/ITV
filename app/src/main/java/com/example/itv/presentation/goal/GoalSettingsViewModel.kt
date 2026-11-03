package com.example.itv.presentation.goal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.itv.composition.goalService
import com.example.itv.data.local.ItvDatabase

class GoalSettingsViewModel(db: ItvDatabase) : ViewModel() {
    internal val repository = goalService(db)
    val homeGoals = repository.observeHomeGoals("demo-guest")
    suspend fun initialize() = repository.createLocalOwner("demo-guest")
    suspend fun setupCompleted() = repository.initialSetupCompleted("demo-guest")
    suspend fun saveAndComplete(writeGoals: suspend () -> Unit) =
        repository.saveSetupAtomically("demo-guest", writeGoals)

    class Factory(private val db: ItvDatabase) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(GoalSettingsViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return GoalSettingsViewModel(db) as T
        }
    }
}
