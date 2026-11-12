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

## Learning insights (schools and factory training)

School page → a group → **Learning insights**. Works for classes and for **Team / department** groups (for example a mill's shift or department: supervisor = class teacher, workers = students, roll no = employee ID).

Per person and course, from Tutor LMS's own quiz data:

| Column | Meaning |
|---|---|
| Attempts to pass | Average attempt number at which each quiz was passed |
| Seconds per question | Attempt time ÷ answered questions (Tutor LMS does not time single questions) |
| Best score, progress, time spent, last active | — |
| Status | On track · Needs help · Stuck · Not started |

Patterns (signs to look at, not judgements):

| Pattern | Rule |
|---|---|
| Strong | Every quiz passed on the first attempt, average 80%+ |
| Rushing | Under 8 seconds per question and failed attempts (guessing) |
| Trying hard but stuck | 1.5 × the group's middle time per question and failed 2+ times |
| Stopped | No activity for 14 days (course not finished), or 2+ quizzes left unfinished |
| Forgetting | Passed a quiz, failed it later |
| Improving | Score up 20+ points between attempts |

Also: the questions the group gets wrong most (fix the lesson, not the people), results by years of experience (0–1, 1–3, 3–5, 5+), and on each person's page the questions they get wrong plus **supervisor notes** (the "why"; only supervisors and the principal see them).

Years of experience: Users → Profile → *Years of experience*, or a third value when adding people to a group (`aslam, EMP-1042, 6`). It counts up by itself.
