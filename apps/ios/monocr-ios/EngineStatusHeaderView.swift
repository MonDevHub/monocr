import SwiftUI

struct EngineStatusHeaderView: View {
    let status: EngineStatus
    @State private var isAnimating = false
    
    var body: some View {
        HStack {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
                .opacity(isAnimating ? 0.4 : 1.0)
            statusText
                .font(.caption)
                .fontWeight(.medium)
                .foregroundColor(.secondary)
                .opacity(isAnimating ? 0.7 : 1.0)
            Spacer()
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
        .background(MonTheme.surfaceColor)
        .onAppear {
            withAnimation(.easeInOut(duration: 1.0).repeatForever(autoreverses: true)) {
                isAnimating = true
            }
        }
    }
    
    private var statusColor: Color {
        switch status {
        case .loading: return MonTheme.warning
        case .ready: return MonTheme.success
        case .processing: return .accentColor
        case .error: return MonTheme.error
        }
    }
    
    /// `Text` built from literals, not looked-up `String`s, so SwiftUI resolves
    /// each one against the in-app language through `\.locale`. This used
    /// `NSLocalizedString`, which follows the system language, so the bar read
    /// "Engine ready" with Burmese or Mon picked in the app.
    private var statusText: Text {
        switch status {
        case .loading: return Text("Initializing engine...")
        case .ready: return Text("Engine ready")
        case .processing(let progress?):
            return Text("Reading: \(progress.done) of \(progress.total) pages done")
        case .processing(nil): return Text("main_scanning")
        case .error(let message): return Text("Error: \(message)")
        }
    }
}
