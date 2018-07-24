'use strict'

const socket = new WebSocket('ws://localhost:9000/ws')

const genRandomId = () => {
    return Math.floor(Math.random() * 0xfffffff)
}

let selfName = 'You'
const pageToken = `${genRandomId()}`
let userToken = null

const initToken = token => {
    userToken = token
}

const lines = [{
    id: 0,
    text: '',
    writer: null,
    date: null
}]

const idToIndex = (id) =>
    lines.findIndex(line => id === line.id)

const initLines = (newLines) => {
    console.log(newLines)
    lines.splice(0, lines.length)
    for (let l of newLines) {
        lines.push({
            id: l.id,
            text: l.text,
            writer: l.writer,
            date: l.date
        })
    }
}

const insertLine = (prevId, id, text, writer, date) => {
    const prevIndex = idToIndex(prevId)
    lines.splice(prevIndex + 1, 0, {
        id, text, writer, date
    })
    return id
}

const updateLine = (id, text, writer, date) => {
    const index = idToIndex(id)
    if (index >= 0 && text !== lines[index].text) {
        lines.splice(index, 1, {
            id, text, writer, date
        })
        return true
    }
    return false
}

const deleteLine = (id) => {
    const index = idToIndex(id)
    if (index >= 0) {
        lines.splice(index, 1)
        return true
    }
    return false
}


const sendGetAll = () => {
    socket.send(JSON.stringify({
        action: "get-all",
        pageToken, userToken
    }))
}

const sendInsertLine = (prevId, id, text) => {
    socket.send(JSON.stringify({
        action: 'insert',
        pageToken, userToken,
        prevId, id, text
    }))
}

const sendUpdateLine = (id, text) => {
    socket.send(JSON.stringify({
        action: 'update',
        pageToken, userToken,
        id, text
    }))
}

const sendDeleteLine = (id) => {
    socket.send(JSON.stringify({
        action: 'delete',
        pageToken, userToken,
        id
    }))
}

const sendKeepAlive = () => {
    socket.send(JSON.stringify({
        action: 'keep-alive',
        pageToken, userToken
    }))
}

let keepAliveID

socket.addEventListener('open', e => {
    console.log('Socket opened', e)

    setTimeout(sendGetAll, 10)

    keepAliveID = setInterval(sendKeepAlive, 30000)
})

socket.addEventListener('close', e => {
    console.log('Socket closed', e)

    clearInterval(keepAliveID)
})

socket.addEventListener('message', e => {
    console.log('Message received', e)

    const data = JSON.parse(e.data)

    if (data.pageToken === undefined) return

    switch (data.action) {
        case 'get-all':
            initToken(data.token)
            initLines(data.lines)
            break
    }

    if (data.pageToken === pageToken) return

    switch (data.action) {
        case 'insert':
            insertLine(data.prevId, data.id, data.text, data.writer, data.insertedAt)
            break

        case 'update':
            updateLine(data.id, data.text, data.writer, data.updatedAt)
            break

        case 'delete':
            deleteLine(data.id, data.writer, data.deletedAt)
            break

        default:
            console.log(`Unknown Action: ${data.action}`)
    }
})


const app = new Vue({
    el: '#app',
    data: {
        lines,
        editingId: -1,
    },

    computed: {
        editorLines() {
            return this.lines.map(l => {
                let r = {}
                Object.assign(r, l)
                r.editing = r.id === this.editingId
                return r
            })
        }
    },

    methods: {
        editing(line) {
            return this.editingId === line.id
        },

        clickLine(e) {
            const input = document.querySelector('#line-input')
            if (input !== null) {
                const id = parseInt(input.dataset.id)
                const text = input.value
                const updated = updateLine(id, text, selfName)
                if (updated) {
                    sendUpdateLine(id, text)
                }
            }
            this.editingId = parseInt(e.currentTarget.dataset.id)
        },

        enterLine(e) {
            const cursor = e.target.selectionStart
            const id = parseInt(e.target.dataset.id)
            const text = e.target.value
            const textCur = text.slice(0, cursor)
            const textNext = text.slice(cursor)
            updateLine(id, textCur, selfName)
            sendUpdateLine(id, textCur)
            const newId = genRandomId()
            this.editingId = insertLine(id, newId, textNext, selfName)
            sendInsertLine(id, newId, textNext)
        },

        upLine(e) {
            const id = parseInt(e.target.dataset.id)
            const text = e.target.value
            const updated = updateLine(id, text, selfName)
            if (updated) {
                sendUpdateLine(id, text)
            }

            const index = idToIndex(id)
            if (index > 0) {
                this.editingId = lines[index - 1].id
            }
        },

        downLine(e) {
            const id = parseInt(e.target.dataset.id)
            const text = e.target.value
            const updated = updateLine(id, text, selfName)
            if (updated) {
                sendUpdateLine(id, text)
            }

            const index = idToIndex(id)
            if (index < lines.length - 1) {
                this.editingId = lines[index + 1].id
            }
        },

        deleteLine(e) {
            if (parseInt(e.target.dataset.id) !== 0 && e.target.selectionStart === 0) {
                const id = parseInt(e.target.dataset.id)
                const text = e.target.value
                const index = idToIndex(id)
                const prevLine = lines[index - 1]
                deleteLine(id)
                sendDeleteLine(id)
                updateLine(prevLine.id, prevLine.text + text, selfName)
                sendUpdateLine(prevLine.id, prevLine.text + text)
                this.editingId = prevLine.id
            }
        }
    },

    updated() {
        const el = document.querySelector('#line-input')
        if (el !== null) el.focus()
    }
})