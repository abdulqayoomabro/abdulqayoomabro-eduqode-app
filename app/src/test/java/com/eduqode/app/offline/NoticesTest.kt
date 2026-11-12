package com.eduqode.app.offline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NoticesTest {
    @Test fun readsNoticesAndSkipsBrokenOnes() {
        val (time, list) = JsonParsers.notices(JSONObject("""{"server_time":1700000000000,"notifications":[
            {"id":"ann-5","type":"announcement","title":"Exam on Monday","body":"Bring a pencil.","course_id":12,"url":"https://x/c","time":1699999999000},
            {"id":"","type":"announcement","title":"no id"},
            {"id":"new-9","type":"new_content","title":""}]}"""))
        assertEquals(1700000000000, time)
        assertEquals(listOf("ann-5"), list.map { it.id })
        assertEquals(12L, list[0].courseId)
        assertEquals("Bring a pencil.", list[0].body)
    }

    @Test fun readsCertificateOfCompletedCourse() {
        val list = JsonParsers.enrolledCourses(JSONObject("""{"courses":[
            {"id":10,"title":"Python","certificate":{"hash":"abc","view_url":"https://x/cert?cert_hash=abc","image_url":"https://x/c.jpg"}},
            {"id":11,"title":"Not done","certificate":null}]}"""))
        assertEquals("abc", list[0].certificate!!.hash)
        assertEquals("Python", list[0].certificate!!.courseTitle)
        assertEquals(null, list[1].certificate)
    }
}
