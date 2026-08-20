package it.unicas.cassitrack.controller;

// Merged into TripController.
//
// Trips and timetable were two screens over the same rows: this controller
// listed runs and edited their calls under /api/v1/timetable, while
// TripController did live monitoring and vehicle reassignment under
// /api/v1/trips. Keeping both meant two URL spaces, two panels and two places
// to look for the same run, so everything now lives under /api/v1/trips:
//
//     GET    /api/v1/trips/stop-times      (was /timetable/stop-times)
//     GET    /api/v1/trips/{id}/stops      (was /timetable/{id})
//     PUT    /api/v1/trips/{id}/times      (was /timetable/{id}/times)
//     DELETE /api/v1/trips/{id}            (was /timetable/{id})
//
// The logic did not move: it is still in TimetableService, which RouteController
// also uses to create a line together with its first run.
//
// This file is intentionally empty and can be deleted.
