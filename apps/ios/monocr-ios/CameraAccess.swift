import Foundation

/**
 What to do when the Camera button is pressed.

 The button used to present `UIImagePickerController(.camera)` unconditionally.
 On a device without a camera (the simulator, some iPads) that is an exception
 or a blank sheet, and with access denied it is a black picker with no word of
 why. The decision is kept free of UIKit and AVFoundation so it can be tested;
 `CameraPermission` in the app maps the system values onto it.
 */
nonisolated enum CameraAccess {
    enum Authorization: Equatable, Sendable {
        case notDetermined, authorized, denied, restricted
    }

    enum Decision: Equatable, Sendable {
        case present
        /// Ask first; present only if the person allows it.
        case requestThenPresent
        case explainUnavailable
        /// The person said no. Settings can change that, so offer the way there.
        case explainDenied
        /// Blocked by Screen Time or device management. Settings in this app
        /// cannot change it, so no Settings button.
        case explainRestricted
    }

    static func decide(isAvailable: Bool, authorization: Authorization) -> Decision {
        guard isAvailable else { return .explainUnavailable }
        switch authorization {
        case .authorized: return .present
        case .notDetermined: return .requestThenPresent
        case .denied: return .explainDenied
        case .restricted: return .explainRestricted
        }
    }
}
