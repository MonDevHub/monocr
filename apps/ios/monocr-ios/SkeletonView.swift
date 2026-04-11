import SwiftUI

struct ShimmerEffect: ViewModifier {
    @State private var phase: CGFloat = 0
    
    func body(content: Content) -> some View {
        content
            .overlay(
                GeometryReader { geo in
                    LinearGradient(
                        gradient: Gradient(colors: [
                            .clear,
                            Color(white: 0.9).opacity(0.3),
                            .clear
                        ]),
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    .rotationEffect(.degrees(30))
                    .offset(x: phase * geo.size.width * 2 - geo.size.width)
                    .onAppear {
                        withAnimation(Animation.linear(duration: 1.5).repeatForever(autoreverses: false)) {
                            phase = 1
                        }
                    }
                }
            )
            .mask(content)
    }
}

extension View {
    func shimmering() -> some View {
        modifier(ShimmerEffect())
    }
}

struct SkeletonRow: View {
    var widthFraction: CGFloat = 1.0
    
    var body: some View {
        GeometryReader { geo in
            RoundedRectangle(cornerRadius: 4)
                .fill(Color.gray.opacity(0.15))
                .frame(width: geo.size.width * widthFraction, height: 14)
        }
        .frame(height: 14)
    }
}

struct SkeletonResultCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Header Skeleton
            HStack {
                RoundedRectangle(cornerRadius: 4)
                    .fill(Color.gray.opacity(0.2))
                    .frame(width: 120, height: 20)
                Spacer()
                HStack(spacing: 8) {
                    Capsule().fill(Color.gray.opacity(0.15)).frame(width: 40, height: 18)
                    Capsule().fill(Color.gray.opacity(0.15)).frame(width: 40, height: 18)
                }
            }
            .shimmering()
            
            Divider()
            
            // Content Skeleton
            VStack(alignment: .leading, spacing: 10) {
                SkeletonRow(widthFraction: 1.0)
                SkeletonRow(widthFraction: 0.85)
                SkeletonRow(widthFraction: 0.70)
                SkeletonRow(widthFraction: 1.0)
            }
            .shimmering()
            
            Spacer().frame(height: 20)
        }
        .padding()
        .background(MonTheme.surfaceColor)
        .cornerRadius(16)
        .shadow(color: Color.black.opacity(0.05), radius: 10, x: 0, y: 5)
    }
}

#Preview {
    ZStack {
        MonTheme.backgroundColor.ignoresSafeArea()
        SkeletonResultCard()
            .padding()
    }
}
