import XCTest

/// Runs the demo on the iOS simulator. The apps live in a WKWebView that Compose hides from the accessibility
/// tree, so the tests tap inside the app by position and check what the host shows in the chat.
final class DemoAppUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launch()
        return app
    }

    private func chip(_ app: XCUIApplication, _ label: String) -> XCUIElement {
        app.buttons[label].firstMatch
    }

    /// The card that holds the contact form is the tall element below the assistant's reply.
    private func tapInForm(_ app: XCUIApplication, x: CGFloat, y: CGFloat) {
        let reply = app.staticTexts["Fill this in and I will pass it on."]
        XCTAssertTrue(reply.waitForExistence(timeout: 10))
        let origin = reply.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
        // Offsets are from the top left of the reply, which sits right above the card.
        origin.withOffset(CGVector(dx: x, dy: y)).tap()
    }

    func testAppStartsWithoutErrors() throws {
        let app = launch()
        XCTAssertTrue(app.staticTexts["Pick something and I will show it."].waitForExistence(timeout: 10))
    }

    func testContactFormToolCallReachesTheHost() throws {
        let app = launch()
        chip(app, "Contact form").tap()
        sleep(6)  // The app loads and reports its size.
        tapInForm(app, x: 36, y: 329)  // The Submit button.
        XCTAssertTrue(app.staticTexts["The app called submit-form"].waitForExistence(timeout: 15))
    }

    func testMessageFromTheAppReachesTheChat() throws {
        let app = launch()
        chip(app, "Contact form").tap()
        sleep(6)
        tapInForm(app, x: 151, y: 329)  // "Tell the assistant".
        let sent = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'I filled in the contact form'"))
        XCTAssertTrue(sent.firstMatch.waitForExistence(timeout: 15))
    }

    func testHostileAppIsBlockedAndReported() throws {
        let app = launch()
        // The chip row scrolls sideways: drag it until the last chip is fully on screen.
        chip(app, "Sales chart").press(forDuration: 0.1, thenDragTo: chip(app, "Show the time"))
        sleep(1)  // Compose updates the accessibility frames after the scroll settles.
        chip(app, "Hostile app").coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        let blocked = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Blocked:'")).firstMatch
        XCTAssertTrue(blocked.waitForExistence(timeout: 30))
        sleep(8)  // The rest of the attacks run after the first.
        // window.open is blocked by WebKit before the UI delegate hears of it, so no "Window" line shows up here.
        for kind in ["ToolUnknown", "ToolNotVisible", "Link", "DisplayMode", "Csp", "Navigation"] {
            let line = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", kind + ":")).firstMatch
            XCTAssertTrue(line.waitForExistence(timeout: 30), kind)
        }
    }
}
