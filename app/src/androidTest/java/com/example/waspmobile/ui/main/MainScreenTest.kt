package com.example.waspmobile.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Emulator screen smoke tests; these do not claim OSRS/Samsung gesture validation. */
class MainScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun setup() { compose.setContent { MainScreen() } }
    @Test fun homeOpensConnectionWithoutDiscardingAppState() {
        compose.onNodeWithText("Connection", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Save connection").performScrollTo().assertExists()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Home", useUnmergedTree = true).performClick()
    }
}
