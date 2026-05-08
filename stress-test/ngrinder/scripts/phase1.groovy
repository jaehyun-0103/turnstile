import static net.grinder.script.Grinder.grinder
import static org.junit.Assert.*
import static org.hamcrest.Matchers.*
import net.grinder.script.GTest
import net.grinder.script.Grinder
import net.grinder.plugin.http.HTTPRequest
import net.grinder.plugin.http.HTTPPluginControl
import HTTPClient.NVPair
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [Phase 1] Turnstile 통합 읽기 부하 테스트 (Optimized)
 * 시나리오: 로그인 -> 대기열 진입 -> 활성화 폴링 -> 좌석 조회(Cache)
 */
@RunWith(GrinderRunner)
class TurnstileIntegratedTest {

    public static GTest test1, test2, test3, test4
    public static HTTPRequest reqLogin, reqEntry, reqStatus, reqSeats
    public static String baseUrl = "http://host.docker.internal:8080"

    @BeforeProcess
    public static void beforeProcess() {
        HTTPPluginControl.getConnectionDefaults().timeout = 6000

        test1 = new GTest(1, "1_Login_API")
        test2 = new GTest(2, "2_Queue_Entry_API")
        test3 = new GTest(3, "3_Queue_Status_API")
        test4 = new GTest(4, "4_Get_Seats_API")

        // HTTPRequest 객체 사전 생성 (에이전트 자원 최적화)
        reqLogin = new HTTPRequest()
        reqEntry = new HTTPRequest()
        reqStatus = new HTTPRequest()
        reqSeats = new HTTPRequest()

        test1.record(reqLogin)
        test2.record(reqEntry)
        test3.record(reqStatus)
        test4.record(reqSeats)
    }

    @BeforeThread
    public void beforeThread() {
        grinder.statistics.delayReports = true
    }

    @Test
    public void doTest() {
        int randomUserId = new Random().nextInt(10000) + 1
        String username = "user${randomUserId}"
        long concertId = 1L
        long scheduleId = 1L

        // --- Step 1: 로그인 및 Identity 토큰 획득 ---
        def loginRes = reqLogin.POST("${baseUrl}/api/v1/auth/login",
                JsonOutput.toJson([username: username]).getBytes(),
                [new NVPair("Content-Type", "application/json")] as NVPair[])
        if (loginRes.statusCode != 200) return
        String identityToken = new JsonSlurper().parseText(loginRes.text).accessToken

        // --- Step 2: 대기열 진입 (Sorted Set 등록) ---
        NVPair[] authHeader = [
                new NVPair("Content-Type", "application/json"),
                new NVPair("Authorization", "Bearer ${identityToken}")
        ]
        def entryRes = reqEntry.POST("${baseUrl}/api/v1/queue/entry",
                JsonOutput.toJson([concertId: concertId]).getBytes(), authHeader)
        assertThat("Step 2 실패: 대기열 진입 불가", entryRes.statusCode, anyOf(is(200), is(201)))

        // --- Step 3: 대기열 상태 폴링 (1초 간격) ---
        boolean isActive = false
        String queueToken = null
        for (int i = 0; i < 30; i++) {
            grinder.sleep(1000)
            def statusRes = reqStatus.GET("${baseUrl}/api/v1/queue/status?concertId=${concertId}", null,
                    [new NVPair("Authorization", "Bearer ${identityToken}")] as NVPair[])
            def statusJson = new JsonSlurper().parseText(statusRes.text)

            if (statusJson.status == "ACTIVE") {
                isActive = true
                queueToken = statusJson.accessToken
                break
            }
        }

        // --- Step 4: 활성 유저 좌석 조회 (캐시 성능 확인) ---
        if (isActive && queueToken != null) {
            def seatsRes = reqSeats.GET("${baseUrl}/api/v1/schedules/${scheduleId}/seats", null,
                    [new NVPair("Authorization", "Bearer ${queueToken}")] as NVPair[])
            assertThat("Step 4 실패: 활성 유저 좌석 조회 불가", seatsRes.statusCode, is(200))
        } else {
            grinder.logger.warn("Polling Timeout: User ${username} did not become ACTIVE")
        }
    }
}