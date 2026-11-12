# EduQode School plugin

Classes, sections and batches on top of Tutor LMS, with dashboards for teachers, parents, students and
the principal. Install `eduqode-school.zip` (Plugins → Add New → Upload). It creates a **School** page
(`[eduqode_school]`); the EduQode app shows a **School** tab when the plugin is active.

## Setup (wp-admin → School)

1. **Classes & batches**: add a *Class* (e.g. Class 7), its *Sections* (A, B — choose the class), or an
   academy *Batch*. Optional class teacher and session.
2. Open a group → **Subjects**: pick a Tutor LMS course and the teacher. Subjects of a class count for
   all its sections. Students of the group are enrolled in the course automatically.
3. **Students**: one per line `email or username, roll no`.
4. **Parents**: parent email + children (email/username). An account is created if needed and the
   parent gets an e-mail to set a password. Parents see only their linked children.
5. **Report cards**: add terms (date ranges); set quiz/assignment weights and the grade scale.

## Who sees what

| User | Sees |
|---|---|
| Administrator / `eqs_principal` capability | every group |
| Teacher (class teacher or subject teacher) | their groups: results, attendance, remarks |
| Parent | their children: subjects, results, attendance, report cards |
| Student | their own page and report cards |

## Results

Read live from Tutor LMS: course progress, best finished attempt of each quiz, marks of graded
assignments (Tutor LMS Pro). Report cards use quiz attempts finished and assignments graded inside the
term, attendance inside the term, and the teacher's remark. "Print / Save as PDF" works on the website
and in the app (Android print screen).

Attendance: Present, Absent, Late (counts as present), Leave. Days in the future cannot be marked.
