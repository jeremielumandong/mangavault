import XCTest
@testable import MangavaultKit

final class ModelDecodingTests: XCTestCase {
    func testSummaryAndDetailDecodeFromContract() throws {
        let json = """
        {"id":42,"title":"Solo Leveling","coverUrl":"https://img.test/c.jpg","coverHeaders":{},"type":"manhwa",
         "status":"ongoing","latestChapter":200.0,"updatedAt":"2026-10-15T08:00:00Z",
         "altTitles":["Na Honjaman Level-Up"],"authors":["Chugong"],"artists":["DUBU"],
         "description":"E-class hunter","genres":["Action","Fantasy"],"chapterCount":201,
         "detailsFetchedAt":"2026-10-17T22:00:00.000Z"}
        """
        let d = try JSONDecoder().decode(MangaDetail.self, from: Data(json.utf8))
        XCTAssertEqual(d.summary.id, 42)
        XCTAssertEqual(d.summary.kind, "manhwa")
        XCTAssertEqual(d.summary.typeLabel, "Manhwa")
        XCTAssertEqual(d.summary.latestChapter, 200)
        XCTAssertEqual(d.chapterCount, 201)
        XCTAssertEqual(d.creditLine, "Chugong · art by DUBU")

        let back = try JSONDecoder().decode(MangaDetail.self, from: JSONEncoder().encode(d))
        XCTAssertEqual(back, d)
    }

    func testSummaryToleratesMissingOptionalFields() throws {
        let s = try JSONDecoder().decode(MangaSummary.self, from: Data(#"{"id":1,"title":"X","type":null}"#.utf8))
        XCTAssertEqual(s.coverHeaders, [:])
        XCTAssertNil(s.kind)
    }

    func testPagesAndHomeDecode() throws {
        let json = """
        {"chapter":{"id":9001,"mangaId":42,"number":112.5,"title":"The Ash Garden","volume":"12",
          "publishedAt":"2026-10-10T00:00:00Z","index":113},
         "pages":[{"index":1,"url":"https://i.test/2.jpg","width":null,"height":null},
                  {"index":0,"url":"https://i.test/1.jpg","width":720,"height":4000}],
         "imageHeaders":{"Referer":"https://site.test/"},"prevChapterId":9000,"nextChapterId":null}
        """
        let p = try JSONDecoder().decode(ChapterPages.self, from: Data(json.utf8))
        XCTAssertEqual(p.imageHeaders["Referer"], "https://site.test/")
        XCTAssertNil(p.nextChapterId)
        let lc = LoadedChapter(p)
        XCTAssertEqual(lc.pages.first?.index, 0)
        XCTAssertEqual(lc.pages.first?.aspect ?? 0, 4000.0 / 720.0, accuracy: 0.0001)

        let home = try JSONDecoder().decode(HomeFeed.self, from: Data(#"{"popular":[],"latest":[],"new":[{"id":3,"title":"N"}]}"#.utf8))
        XCTAssertEqual(home.newTitles.first?.id, 3)
    }

    func testBaseURL() {
        XCTAssertEqual(APIClient.baseURL(domain: "api.example.com")?.absoluteString, "https://api.example.com/v1")
        XCTAssertEqual(APIClient.baseURL(domain: " api.example.com/ ")?.absoluteString, "https://api.example.com/v1")
        XCTAssertEqual(APIClient.baseURL(domain: "http://localhost:8787")?.absoluteString, "http://localhost:8787/v1")
        XCTAssertNil(APIClient.baseURL(domain: ""))
    }

    func testQueryItemsRepeatGenres() {
        var f = SearchFilters()
        f.cycle("Action")
        f.cycle("Romance")
        f.cycle("Romance")
        f.status = "Ongoing"
        f.sort = .latest
        let items = f.query(searching: false).items
        XCTAssertEqual(items.filter { $0.name == "genre" }.map(\.value), ["Action"])
        XCTAssertEqual(items.filter { $0.name == "excludeGenre" }.map(\.value), ["Romance"])
        XCTAssertEqual(items.first { $0.name == "status" }?.value, "ongoing")
        XCTAssertEqual(items.first { $0.name == "sort" }?.value, "latest")
        XCTAssertEqual(f.count, 4)
        f.cycle("Romance")
        XCTAssertTrue(f.exclude.isEmpty)
        XCTAssertNil(SearchFilters().query(searching: true).items.first { $0.name == "sort" })
        XCTAssertEqual(SearchFilters().query(searching: false).items.first { $0.name == "sort" }?.value, "popular")
    }
}

final class FormattingTests: XCTestCase {
    func testChapterNumbers() {
        XCTAssertEqual(Fmt.number(112), "112")
        XCTAssertEqual(Fmt.number(112.5), "112.5")
        XCTAssertEqual(Fmt.number(10.25), "10.25")
        XCTAssertEqual(Fmt.number(nil), "?")
        let c = Chapter(id: 1, mangaId: 1, number: 12, title: "Embers", volume: "2", publishedAt: nil, index: 11)
        XCTAssertEqual(Fmt.chapter(c), "Ch. 12")
        XCTAssertEqual(Fmt.chapterSub(c), "Vol. 2 · Embers")
    }

    func testJumpBuckets() {
        XCTAssertEqual(jumpBuckets(latest: 31).map { "\($0.lowerBound)–\($0.upperBound)" }, ["1–10", "11–20", "21–30", "31–31"])
        XCTAssertEqual(jumpBuckets(latest: 118).first, 1...20)
        let big = jumpBuckets(latest: 402)
        XCTAssertEqual(big.count, 9)
        XCTAssertEqual(big.last, 401...402)
        XCTAssertEqual(jumpBuckets(latest: 2000).count, 12)
    }

    func testHistoryDays() {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC")!
        let now = ISO.date("2026-10-15T12:00:00Z")!
        XCTAssertEqual(HistoryDay.of(ISO.date("2026-10-15T01:00:00Z")!, now: now, calendar: cal), .today)
        XCTAssertEqual(HistoryDay.of(ISO.date("2026-10-14T23:00:00Z")!, now: now, calendar: cal), .yesterday)
        XCTAssertEqual(HistoryDay.of(ISO.date("2026-10-10T08:00:00Z")!, now: now, calendar: cal), .thisWeek)
        XCTAssertEqual(HistoryDay.of(ISO.date("2026-10-02T08:00:00Z")!, now: now, calendar: cal), .earlier)
        XCTAssertEqual(HistoryDay.timeLabel(ISO.date("2026-10-02T08:00:00Z")!, now: now, calendar: cal), "Oct 2")
    }

    func testISO() {
        XCTAssertTrue(ISO.isLater("2026-10-17T22:00:00.001Z", than: "2026-10-17T22:00:00Z"))
        XCTAssertFalse(ISO.isLater("2026-10-17T22:00:00Z", than: "2026-10-17T22:00:00.000Z"))
        XCTAssertNotNil(ISO.date("2024-01-02"))
        XCTAssertTrue(ISO.now().hasSuffix("Z"))
    }
}

final class SyncLogicTests: XCTestCase {
    let manga = MangaSummary(id: 7, title: "T", latestChapter: 20)

    func testLibraryLastWriteWins() {
        let local = LibraryEntry(mangaId: 7, category: "Reading", addedAt: "2026-10-01T00:00:00.000Z", updatedAt: "2026-10-10T00:00:00.000Z")
        let older = RemoteLibraryItem(manga: manga, category: "Completed", addedAt: "2026-10-01T00:00:00.000Z", updatedAt: "2026-10-09T00:00:00.000Z", deleted: false, unreadCount: 0)
        XCTAssertNil(Merge.library(local, older))
        var newer = older
        newer.updatedAt = "2026-10-11T00:00:00.000Z"
        newer.deleted = true
        XCTAssertEqual(Merge.library(local, newer)?.deleted, true)
        XCTAssertEqual(Merge.library(nil, older)?.category, "Completed")
    }

    func testProgressMergeKeepsIndexOnlyForSameChapter() {
        let local = ProgressEntry(mangaId: 7, chapterId: 100, chapterNumber: 5, chapterIndex: 4, page: 3, pageCount: 20, completed: false, updatedAt: "2026-10-01T00:00:00.000Z")
        let same = RemoteProgress(mangaId: 7, chapterId: 100, chapterNumber: 5, page: 9, pageCount: 20, completed: false, updatedAt: "2026-10-02T00:00:00.000Z")
        XCTAssertEqual(Merge.progress(local, same)?.chapterIndex, 4)
        XCTAssertEqual(Merge.progress(local, same)?.page, 9)
        var other = same
        other.chapterId = 101
        XCTAssertNil(Merge.progress(local, other)?.chapterIndex)
    }

    func testUnreadAndContinue() {
        let p = ProgressEntry(mangaId: 7, chapterId: 1, chapterNumber: 15, chapterIndex: 14, page: 1, pageCount: 10, completed: false, updatedAt: "2026-10-02T00:00:00.000Z")
        XCTAssertEqual(Reading.unread(latest: 20, progress: p), 5)
        XCTAssertEqual(Reading.unread(latest: 20, progress: nil), 20)
        var done = p
        done.chapterNumber = 20
        done.completed = true
        done.mangaId = 8
        let list = Reading.continueList([done, p], latest: { _ in 20 })
        XCTAssertEqual(list.map(\.mangaId), [7])
    }

    func testIsRead() {
        let p = ProgressEntry(mangaId: 7, chapterId: 5, chapterNumber: 5, chapterIndex: 4, page: 1, pageCount: 10, completed: false, updatedAt: "x")
        let before = Chapter(id: 4, mangaId: 7, number: 4, index: 3)
        let current = Chapter(id: 5, mangaId: 7, number: 5, index: 4)
        XCTAssertTrue(Reading.isRead(before, mark: nil, progress: p))
        XCTAssertFalse(Reading.isRead(current, mark: nil, progress: p))
        XCTAssertFalse(Reading.isRead(before, mark: ReadMark(chapterId: 4, mangaId: 7, read: false, updatedAt: "x"), progress: p))
    }

    func testCursorMargin() {
        XCTAssertEqual(SyncCursors.since("2026-10-17T00:00:00.000Z"), "2026-10-10T00:00:00.000Z")
        XCTAssertNil(SyncCursors.since(nil))
    }

    func testCategories() {
        XCTAssertEqual(Categories.all(custom: ["Weekly reads"], used: ["Reading", nil, "Old"]), ["Reading", "Plan to read", "Completed", "Weekly reads", "Old"])
        XCTAssertEqual(Categories.all(custom: [], used: [], removed: ["Plan to read"]), ["Reading", "Completed"])
        // Still on a synced entry: it stays visible.
        XCTAssertEqual(Categories.all(custom: [], used: ["Completed"], removed: ["Completed"]), ["Reading", "Plan to read", "Completed"])
    }

    func testHiddenProgressLeavesContinueAndSyncs() {
        var p = ProgressEntry(mangaId: 7, chapterId: 1, chapterNumber: 15, chapterIndex: 14, page: 1, pageCount: 10, completed: false, updatedAt: "2026-10-02T00:00:00.000Z")
        XCTAssertEqual(Reading.continueList([p], latest: { _ in 20 }).count, 1)
        p.hidden = true
        XCTAssertTrue(Reading.continueList([p], latest: { _ in 20 }).isEmpty)
        XCTAssertTrue(p.putItem.hidden)
        let remote = RemoteProgress(mangaId: 7, chapterId: 1, chapterNumber: 15, page: 1, pageCount: 10, completed: false, updatedAt: "2026-10-03T00:00:00.000Z", hidden: true)
        XCTAssertEqual(Merge.progress(nil, remote)?.isHidden, true)
        let old = RemoteProgress(mangaId: 7, chapterId: 1, chapterNumber: 15, page: 1, pageCount: 10, completed: false, updatedAt: "2026-10-03T00:00:00.000Z")
        XCTAssertEqual(Merge.progress(nil, old)?.isHidden, false)
    }

    func testStatsDecode() throws {
        let json = #"{"series":12345,"chapters":456789,"latestUpdateAt":null}"#.data(using: .utf8)!
        let s = try JSONDecoder().decode(CatalogStats.self, from: json)
        XCTAssertEqual(s.series, 12345)
        XCTAssertEqual(Fmt.count(s.series), "12,345")
        XCTAssertNil(s.latestUpdateAt)
    }
}

final class ReaderLogicTests: XCTestCase {
    func testTapZones() {
        XCTAssertEqual(TapZones.direction(mode: .ltr, x: 0.9, y: 0.5, inverted: false), 1)
        XCTAssertEqual(TapZones.direction(mode: .rtl, x: 0.1, y: 0.5, inverted: false), 1)
        XCTAssertEqual(TapZones.direction(mode: .rtl, x: 0.1, y: 0.5, inverted: true), -1)
        XCTAssertEqual(TapZones.direction(mode: .pagedV, x: 0.9, y: 0.5, inverted: false), 0)
        XCTAssertEqual(TapZones.direction(mode: .pagedV, x: 0.5, y: 0.8, inverted: false), 1)
        XCTAssertEqual(TapZones.direction(mode: .ltr, x: 0.5, y: 0.5, inverted: false), 0)
    }

    func testDefaultModes() {
        XCTAssertEqual(ReaderMode.defaultMode(forType: "manga"), .rtl)
        XCTAssertEqual(ReaderMode.defaultMode(forType: "manhwa"), .vertical)
        XCTAssertEqual(ReaderMode.defaultMode(forType: nil), .vertical)
    }

    func testSequence() {
        let a = LoadedChapter(chapter: Chapter(id: 1, mangaId: 9, number: 1, index: 0), pages: [Page(index: 0, url: "a"), Page(index: 1, url: "b")], next: 2)
        let b = LoadedChapter(chapter: Chapter(id: 2, mangaId: 9, number: 2, index: 1), pages: [Page(index: 0, url: "c")])
        let v = ReaderSequence.build([a, b], mode: .vertical)
        XCTAssertEqual(v.map(\.id), ["p1-1", "p1-2", "d1", "p2-1", "e2"])
        let paged = ReaderSequence.build([a], mode: .rtl)
        XCTAssertEqual(paged.map(\.id), ["p1-1", "p1-2", "t1"])
        XCTAssertEqual(ReaderSequence.index(of: 2, page: 1, in: v), 3)
        XCTAssertEqual(ReaderSequence.pagesAhead(of: 0, count: 2, in: v).map(\.id), ["p1-2", "p2-1"])
    }
}

final class GoogleOAuthTests: XCTestCase {
    func testPKCEAndCallback() throws {
        // RFC 7636 appendix B.
        XCTAssertEqual(GoogleOAuth.challenge(for: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"), "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let g = GoogleOAuth(clientId: "123-abc.apps.googleusercontent.com", verifier: "v", state: "s")
        XCTAssertEqual(g.callbackScheme, "com.googleusercontent.apps.123-abc")
        XCTAssertEqual(g.redirectURI, "com.googleusercontent.apps.123-abc:/oauthredirect")
        XCTAssertEqual(try g.code(from: URL(string: "com.googleusercontent.apps.123-abc:/oauthredirect?state=s&code=4/xyz")!), "4/xyz")
        XCTAssertThrowsError(try g.code(from: URL(string: "x:/r?state=other&code=1")!))
        XCTAssertTrue(GoogleOAuth.randomVerifier().count >= 43)
    }
}
