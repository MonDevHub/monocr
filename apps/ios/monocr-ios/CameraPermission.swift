import AVFoundation
import SwiftUI
import UIKit

/// The system's camera state, mapped onto `CameraAccess` so the decision about
/// what the Camera button does is the tested one.
enum CameraPermission {
    static var isAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    static var authorization: CameraAccess.Authorization {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return .authorized
        case .notDetermined: return .notDetermined
        case .restricted: return .restricted
        case .denied: return .denied
        @unknown default: return .denied
        }
    }

    static func decide() -> CameraAccess.Decision {
        CameraAccess.decide(isAvailable: isAvailable, authorization: authorization)
    }

    static func request() async -> Bool {
        await AVCaptureDevice.requestAccess(for: .video)
    }

    static func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

/// Why the camera did not open, as an alert. Settings is offered only where it
/// can change the answer.
struct CameraAccessAlert: ViewModifier {
    @Binding var decision: CameraAccess.Decision?

    func body(content: Content) -> some View {
        content.alert(
            title,
            isPresented: Binding(get: { decision != nil }, set: { if !$0 { decision = nil } })
        ) {
            if decision == .explainDenied {
                Button("Open Settings") { CameraPermission.openSettings() }
                Button("Cancel", role: .cancel) {}
            } else {
                Button("OK", role: .cancel) {}
            }
        } message: {
            message
        }
    }

    private var title: Text {
        switch decision {
        case .explainDenied: return Text("Camera access is off")
        case .explainRestricted: return Text("Camera access is restricted")
        default: return Text("Camera not available")
        }
    }

    private var message: Text {
        switch decision {
        case .explainDenied:
            return Text("Allow camera access for MonOCR in Settings to photograph pages.")
        case .explainRestricted:
            return Text("Camera use is restricted on this device, so MonOCR cannot open it.")
        default:
            return Text("This device has no camera. Choose a photo or a PDF instead.")
        }
    }
}
