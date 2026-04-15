//
//  ContentView.swift
//  MobileTest
//

import SwiftUI

struct ContentView: View {
    @State private var hashValue: String = "..."
    @State private var nreplPort: Int = 0
    @State private var logLines: [String] = []
    @State private var greaseMessage: String = GreaseHook.shared.message

    private let ticker = Timer.publish(every: 0.5, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {

            // ── Title ──────────────────────────────────────────────────────
            Text("CLOJURE BRIDGE")
                .font(.system(size: 30, weight: .black))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(Color.indigo)
                .foregroundColor(.white)

            // ── Status cards ───────────────────────────────────────────────
            VStack(alignment: .leading, spacing: 10) {

                HStack(spacing: 6) {
                    Text("Hash")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(hashValue)
                        .font(.system(.body, design: .monospaced))
                        .fontWeight(.semibold)
                }

                HStack(spacing: 8) {
                    Circle()
                        .fill(nreplPort > 0 ? Color.green : Color.orange)
                        .frame(width: 10, height: 10)
                    if nreplPort > 0 {
                        Text("nREPL running on port \(nreplPort)")
                            .fontWeight(.semibold)
                            .foregroundColor(.green)
                    } else {
                        Text("nREPL starting…")
                            .fontWeight(.semibold)
                            .foregroundColor(.orange)
                    }
                }

                HStack(spacing: 6) {
                    Text("REPL")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(greaseMessage)
                        .font(.system(.body, design: .monospaced))
                        .fontWeight(.semibold)
                        .foregroundColor(.cyan)
                }
            }
            .padding()
            .background(Color(.systemGray6))

            // ── Log output ─────────────────────────────────────────────────
            HStack {
                Text("Output")
                    .font(.caption)
                    .fontWeight(.semibold)
                    .foregroundColor(.secondary)
                Spacer()
                Text("\(logLines.count) lines")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal)
            .padding(.top, 8)
            .padding(.bottom, 4)

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 1) {
                        ForEach(Array(logLines.enumerated()), id: \.offset) { idx, line in
                            Text(line)
                                .font(.system(size: 11, design: .monospaced))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .foregroundColor(logLineColor(line))
                                .id(idx)
                        }
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                }
                .background(Color.black.opacity(0.88))
                .onChange(of: logLines.count) { count in
                    guard count > 0 else { return }
                    withAnimation(.easeOut(duration: 0.15)) {
                        proxy.scrollTo(count - 1)
                    }
                }
            }
            .padding(.horizontal)
            .padding(.bottom)
        }
        .ignoresSafeArea(edges: .top)
        .onReceive(ticker) { _ in poll() }
        .onAppear {
            let raw = call_hash_code()
            hashValue = String(format: "%08x", UInt32(bitPattern: Int32(truncatingIfNeeded: raw)))
            poll()
        }
    }

    private func logLineColor(_ line: String) -> Color {
        if line.localizedCaseInsensitiveContains("error") { return .red }
        if line.hasPrefix("[Clojure]")                   { return .green }
        if line.hasPrefix("[Bridge]")                    { return Color(red: 1, green: 0.85, blue: 0) }
        return Color.white.opacity(0.85)
    }

    private func poll() {
        let port = Int(call_nrepl_port())
        if port != nreplPort { nreplPort = port }

        let msg = GreaseHook.shared.message
        if msg != greaseMessage { greaseMessage = msg }

        guard let ptr = bridge_get_logs() else { return }
        let raw = String(cString: ptr)
        let lines = raw.split(separator: "\n", omittingEmptySubsequences: true).map(String.init)
        if lines.count != logLines.count { logLines = lines }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
