package com.busnotice.commute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.busnotice.commute.Candidate
import com.busnotice.commute.Leg
import com.busnotice.commute.Trip

sealed interface Screen {
    data object Home : Screen
    data object Edit : Screen
    data class PickBus(val legIndex: Int) : Screen
    data class PickMetro(val legIndex: Int) : Screen
    data class EditShuttle(val legIndex: Int, val candidateIndex: Int) : Screen
}

@Composable
fun App(resumeTick: Int) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var draft by remember { mutableStateOf(Trip()) }

    fun addCandidate(legIndex: Int, c: Candidate, replaceAt: Int = -1) {
        val legs = draft.legs.toMutableList()
        while (legs.size <= legIndex) legs += Leg(emptyList())
        val cands = legs[legIndex].candidates.toMutableList()
        if (replaceAt in cands.indices) cands[replaceAt] = c else cands += c
        legs[legIndex] = legs[legIndex].copy(candidates = cands)
        draft = draft.copy(legs = legs)
        screen = Screen.Edit
    }

    BackHandler(enabled = screen != Screen.Home) {
        screen = if (screen == Screen.Edit) Screen.Home else Screen.Edit
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            resumeTick = resumeTick,
            onNew = { draft = Trip(legs = listOf(Leg(emptyList()))); screen = Screen.Edit },
            onEdit = { draft = it; screen = Screen.Edit },
        )
        Screen.Edit -> TripEditor(
            trip = draft,
            onChange = { draft = it },
            onBack = { screen = Screen.Home },
            onSaved = { screen = Screen.Home },
            onAddBus = { screen = Screen.PickBus(it) },
            onAddMetro = { screen = Screen.PickMetro(it) },
            onEditShuttle = { leg, idx -> screen = Screen.EditShuttle(leg, idx) },
        )
        is Screen.PickBus -> BusPicker(
            onBack = { screen = Screen.Edit },
            onPicked = { addCandidate(s.legIndex, it) },
        )
        is Screen.PickMetro -> MetroPicker(
            onBack = { screen = Screen.Edit },
            onPicked = { addCandidate(s.legIndex, it) },
        )
        is Screen.EditShuttle -> ShuttleEditor(
            initial = draft.legs.getOrNull(s.legIndex)?.candidates?.getOrNull(s.candidateIndex),
            onBack = { screen = Screen.Edit },
            onDone = { addCandidate(s.legIndex, it, s.candidateIndex) },
        )
    }
}
