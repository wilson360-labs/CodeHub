import SwiftUI

private enum CodeHubStyle {
    static let background = Color(red: 0.035, green: 0.055, blue: 0.085)
    static let surface = Color(red: 0.075, green: 0.105, blue: 0.145)
    static let accent = Color(red: 0.32, green: 0.84, blue: 0.76)
    static let secondary = Color(red: 0.65, green: 0.72, blue: 0.81)
}

struct HomeView: View {
    private let siteURL = URL(string: "https://wilson360-labs.vercel.app/")!

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header
                    featuredCard
                    sectionTitle
                    quickLinks
                    footer
                }
                .padding(.horizontal, 22)
                .padding(.top, 18)
                .padding(.bottom, 32)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .background(CodeHubStyle.background.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
        }
        .tint(CodeHubStyle.accent)
    }

    private var header: some View {
        HStack(spacing: 14) {
            Image(systemName: "square.stack.3d.up.fill")
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(CodeHubStyle.background)
                .frame(width: 52, height: 52)
                .background(CodeHubStyle.accent, in: RoundedRectangle(cornerRadius: 17))

            VStack(alignment: .leading, spacing: 3) {
                Text("CodeHub")
                    .font(.system(size: 25, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                Text("Tu espacio digital")
                    .font(.subheadline)
                    .foregroundStyle(CodeHubStyle.secondary)
            }
            Spacer()
            Text("iOS")
                .font(.caption.weight(.bold))
                .foregroundStyle(CodeHubStyle.accent)
                .padding(.horizontal, 11)
                .padding(.vertical, 7)
                .background(CodeHubStyle.accent.opacity(0.12), in: Capsule())
        }
        .padding(.vertical, 8)
    }

    private var featuredCard: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Label("CODEHUB", systemImage: "sparkle")
                    .font(.caption.weight(.bold))
                    .tracking(1.2)
                    .foregroundStyle(CodeHubStyle.accent)
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(CodeHubStyle.secondary)
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("Todo lo que necesitas, en un solo lugar.")
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Explora herramientas, proyectos y servicios de CodeHub.")
                    .font(.subheadline)
                    .foregroundStyle(CodeHubStyle.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Link(destination: siteURL) {
                HStack(spacing: 9) {
                    Text("Explorar CodeHub")
                    Image(systemName: "arrow.right")
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(CodeHubStyle.background)
                .padding(.horizontal, 17)
                .padding(.vertical, 12)
                .background(CodeHubStyle.accent, in: Capsule())
            }
            .accessibilityHint("Abre CodeHub en Safari")
        }
        .padding(22)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 28)
                .fill(LinearGradient(colors: [CodeHubStyle.surface, Color(red: 0.08, green: 0.18, blue: 0.21)], startPoint: .topLeading, endPoint: .bottomTrailing))
                .overlay {
                    RoundedRectangle(cornerRadius: 28)
                        .stroke(.white.opacity(0.08), lineWidth: 1)
                }
        }
    }

    private var sectionTitle: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text("Tu plataforma")
                .font(.title3.weight(.bold))
                .foregroundStyle(.white)
            Text("Una experiencia nativa en desarrollo")
                .font(.subheadline)
                .foregroundStyle(CodeHubStyle.secondary)
        }
    }

    private var quickLinks: some View {
        VStack(spacing: 12) {
            Link(destination: siteURL) {
                destinationRow(icon: "square.grid.2x2.fill", title: "Tienda CodeHub", subtitle: "Aplicaciones y proyectos open source", color: CodeHubStyle.accent)
            }
            Link(destination: siteURL) {
                destinationRow(icon: "sparkles", title: "JARVIS", subtitle: "Asistente de CodeHub", color: Color(red: 0.67, green: 0.72, blue: 1))
            }
            Link(destination: siteURL) {
                destinationRow(icon: "cloud.sun.fill", title: "Clima y alertas", subtitle: "Información y avisos en línea", color: Color(red: 0.98, green: 0.72, blue: 0.39))
            }
        }
        .buttonStyle(.plain)
    }

    private func destinationRow(icon: String, title: String, subtitle: String, color: Color) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(color)
                .frame(width: 46, height: 46)
                .background(color.opacity(0.13), in: RoundedRectangle(cornerRadius: 15))
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(CodeHubStyle.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            Image(systemName: "arrow.up.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(CodeHubStyle.secondary)
        }
        .padding(14)
        .background(CodeHubStyle.surface, in: RoundedRectangle(cornerRadius: 20))
        .overlay {
            RoundedRectangle(cornerRadius: 20)
                .stroke(.white.opacity(0.06), lineWidth: 1)
        }
    }

    private var footer: some View {
        Text("CodeHub para iPhone está comenzando. Las funciones nativas se irán incorporando por etapas.")
            .font(.footnote)
            .foregroundStyle(CodeHubStyle.secondary.opacity(0.85))
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.top, 6)
    }
}
