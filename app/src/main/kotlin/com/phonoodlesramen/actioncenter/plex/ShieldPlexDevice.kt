package com.phonoodlesramen.actioncenter.plex

/**
 * How the TVs name themselves to Plex on every request, as they always have: a SHIELD on
 * Android 11 (a Google TV says the same). plex.tv lists it under Authorized Devices.
 */
val SHIELD_PLEX_DEVICE = PlexDevice(platform = "Android", platformVersion = "11", device = "SHIELD Android TV", deviceName = "SHIELD")
