//
//  monocr_iosApp.swift
//  monocr-ios
//
//  Created by Zin Min Htut Oo on 3/14/26.
//

import SwiftUI
import CoreText
import SwiftData

@main
struct monocr_iosApp: App {
    let container: ModelContainer

    init() {
        self.container = Self.createModelContainer()
        registerFonts()
        
        let container = self.container
        // Boot up background sync (in Task to handle actor-isolated method)
        Task {
            await SyncService.shared.initialize(with: container)
        }
    }
    
    /// Self-Healing ModelContainer setup (Wipes database on failure)
    private static func createModelContainer() -> ModelContainer {
        let schema = Schema([HistoryRecord.self])
        let config = ModelConfiguration("MonHistory", schema: schema)
        
        do {
            return try ModelContainer(for: schema, configurations: [config])
        } catch {
            MonLog_e("Initial SwiftData setup failed. Attempting self-healing (Reset Store)...", error: error)
            
            // Critical Recovery Path: Attempt to wipe the local store on failure
            let url = config.url
            MonLog_w("Deleting corrupted store at: \(url.path)")
            try? FileManager.default.removeItem(at: url)
            
            // Retry once
            do {
                return try ModelContainer(for: schema, configurations: [config])
            } catch {
                MonLog_e("Permanent store failure. Falling back to in-memory mode.", error: error)
            }
            
            // Last-resort fallback to prevent app from being unlaunchable
            let fallbackConfig = ModelConfiguration(isStoredInMemoryOnly: true)
            return try! ModelContainer(for: schema, configurations: [fallbackConfig])
        }
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .modelContainer(container)
    }
    
    private func registerFonts() {
        let fonts = ["pyidaungsu_regular", "pyidaungsu_bold"]
        for font in fonts {
            let url = Bundle.main.url(forResource: font, withExtension: "ttf") ?? 
                     Bundle.main.url(forResource: font, withExtension: "ttf", subdirectory: "Fonts")
            
            guard let fontURL = url else {
                MonLog_e("Failed to find font file: \(font)")
                continue
            }
            
            var error: Unmanaged<CFError>?
            // Using .process scope for SwiftUI live preview/app session
            if !CTFontManagerRegisterFontsForURL(fontURL as CFURL, .process, &error) {
                MonLog_d("Registration result for \(font): \(String(describing: error))")
            }
        }
        
        // Final Verification
        let family = "Pyidaungsu"
        if UIFont.familyNames.contains(family) {
            MonLog_i("Font Family '\(family)' is successfully registered and available.")
        } else {
            MonLog_w("Font Family '\(family)' not found after registration. Falling back to system fonts.")
        }
    }
}
