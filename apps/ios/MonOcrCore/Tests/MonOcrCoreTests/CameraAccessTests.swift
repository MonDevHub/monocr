import Foundation
import Testing

@testable import MonOcrCore

/// What the Camera button does. It used to present the picker unconditionally:
/// a crash or blank sheet without a camera, a black picker with access denied.
struct CameraAccessTests {
    /// No camera means no picker, whatever the permission says. The simulator
    /// is the everyday case.
    @Test func noCameraIsExplainedBeforePermissionIsConsidered() {
        for status in [CameraAccess.Authorization.authorized, .notDetermined, .denied, .restricted] {
            #expect(CameraAccess.decide(isAvailable: false, authorization: status) == .explainUnavailable)
        }
    }

    @Test func eachPermissionStateGetsItsOwnAnswer() {
        #expect(CameraAccess.decide(isAvailable: true, authorization: .authorized) == .present)
        #expect(CameraAccess.decide(isAvailable: true, authorization: .notDetermined) == .requestThenPresent)
        #expect(CameraAccess.decide(isAvailable: true, authorization: .denied) == .explainDenied)
        #expect(CameraAccess.decide(isAvailable: true, authorization: .restricted) == .explainRestricted)
    }
}
