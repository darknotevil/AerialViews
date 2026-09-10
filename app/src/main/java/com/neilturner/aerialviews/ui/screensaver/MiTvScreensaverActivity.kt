package com.neilturner.aerialviews.ui.screensaver

/**
 * Entry point used by MIUI TV (Xiaomi/Redmi, Android 9) to start the screensaver.
 *
 * The stock screensaver app fires com.mitv.gallery.action.SHOW_SCREENSAVER from
 * com.mitv.screensaver/RenderActivity with a bare intent - no FLAG_ACTIVITY_NEW_TASK,
 * no extras. Without a launch mode of its own the activity would be placed into the
 * caller's task, and the finishAndRemoveTask() call in TestActivity.onStop() would
 * then tear that whole task down, taking the app the user was watching with it.
 *
 * singleInstance plus a dedicated task affinity keeps the screensaver in a task of
 * its own regardless of how the caller launched it, and makes a repeated trigger
 * reuse the running instance instead of stacking a second copy on top.
 *
 * Behaviour is otherwise identical to TestActivity.
 */
class MiTvScreensaverActivity : TestActivity()
