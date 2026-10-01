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
    let persistentHistoryAvailable: Bool

    init() {
        let storage = Self.createModelContainer()
        self.container = storage.container
        self.persistentHistoryAvailable = storage.persistent
        registerFonts()
        
        let container = self.container
        // Boot up background sync (in Task to handle actor-isolated method)
        Task {
            await SyncService.shared.initialize(with: container)
        }
    }
    
    /// Keep existing history intact if opening or migrating its store fails.
    private static func createModelContainer() -> (container: ModelContainer, persistent: Bool) {
        let schema = Schema([HistoryRecord.self])
        let config = ModelConfiguration("MonHistory", schema: schema)
        
        do {
            return (try ModelContainer(for: schema, configurations: [config]), true)
        } catch {
            // An incompatible migration is not permission to erase user scans.
            // Preserve the persistent store so a later build can recover it.
            MonLog_e("Persistent history could not be opened; preserving its files and using temporary storage.", error: error)

            // Last-resort fallback to prevent app from being unlaunchable
            let fallbackConfig = ModelConfiguration(isStoredInMemoryOnly: true)
            return (try! ModelContainer(for: schema, configurations: [fallbackConfig]), false)
        }
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
                .safeAreaInset(edge: .top) {
                    if !persistentHistoryAvailable {
                        Text("History is temporarily unavailable. New scans will not be kept after closing the app.")
                            .font(.caption)
                            .foregroundColor(.orange)
                            .padding(8)
                    }
                }
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
