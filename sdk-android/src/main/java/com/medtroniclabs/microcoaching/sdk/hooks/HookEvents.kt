package com.medtroniclabs.microcoaching.sdk.hooks

// TODO: internal sealed class for all hook-triggered events.
// Events passed from SpiceHookAdapter through CoachingDecisionEngine.
// Examples:
//   MorningOpen(chwId: String, pendingPatients: Int?)
//   AssessmentReady(assessmentData: Map<String, Any>)
//   RiskFlagObserved(riskLevel: String, patientId: String)
//   VisitCompleted(encounterId: String)
//   ConnectivityChanged(isOnline: Boolean)
