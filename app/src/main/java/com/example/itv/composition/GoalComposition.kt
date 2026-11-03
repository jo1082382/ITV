package com.example.itv.composition

import com.example.itv.application.goals.GoalService
import com.example.itv.data.local.ItvDatabase
import com.example.itv.infrastructure.goals.RoomGoalStore

fun goalService(db: ItvDatabase): GoalService = GoalService(RoomGoalStore(db))
