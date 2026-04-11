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
            Text(statusText)
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
        case .error: return MonTheme.error
        }
    }
    
    private var statusText: String {
        switch status {
        case .loading: return NSLocalizedString("Initializing engine...", comment: "")
        case .ready: return NSLocalizedString("Engine ready", comment: "")
        case .error(let msg): 
            let format = NSLocalizedString("Error: %@", comment: "")
            return String(format: format, msg)
        }
    }
}
