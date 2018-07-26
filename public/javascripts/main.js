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

const typing = {}
const typingIds = {}

const chats = []

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
    if (lines.length < 2000) {
        const prevIndex = idToIndex(prevId)
        lines.splice(prevIndex + 1, 0, {
            id, text, writer, date
        })
        return id
    }
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

const pushChat = (message, writer, date) => {
    chats.push({
        message, writer, date
    })
}

const initChat = (newChats) => {
    console.log(newChats)
    for (let c of newChats) {
        pushChat(c.message, c.writer, c.date)
    }
}

const setTyping = (id, writer) => {
    console.log(id, writer)
    if (typing[writer] !== undefined) {
        typingIds[typing[writer]] = null
    }
    if (id !== null) {
        typing[writer] = id
        typingIds[id] = writer
    }
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

const sendTyping = (id) => {
    socket.send(JSON.stringify({
        action: 'typing',
        pageToken, userToken,
        id
    }))
}


const sendChatPost = (message) => {
    socket.send(JSON.stringify({
        action: 'chat-post',
        pageToken, userToken,
        message
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

    switch (data.action) {
        case 'get-all':
            initToken(data.token)
            initLines(data.lines)
            initChat(data.chats)
            break

        case 'chat-post':
            pushChat(data.message, data.writer, data.postedAt)
            break
    }

    if (data.pageToken === undefined) return
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

        case 'typing':
            setTyping(data.id, data.writer)
            break

        default:
            console.log(`Unknown Action: ${data.action}`)
    }
})



const app = new Vue({
    el: '#app',

    components: {
        'parse-md': {
            /*================= WIP
            render(h) {
                const r = (h, s) => {
                    s.match(/(.*?)()()/)
                }

                return r(h, this.$slots.default)
            }
            */
        }
    },

    data: {
        lines,
        chats,
        editingId: -1,
        typingId: null,
        tipIndex: null
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
            this.changeTyping(null)
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
            this.changeTyping(null)
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
                this.changeTyping(null)
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
                this.changeTyping(null)
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
                this.changeTyping(null)
            }
        },

        changeTyping(id) {
            const prevTypingId = this.typingId
            this.typingId = id
            if (prevTypingId !== id) {
                sendTyping(id)
            }
        },

        inputLine(e) {
            this.changeTyping(e.target.dataset.id)
        },

        show(q) {
            document.querySelector(q).style.display = null
        },

        hide(q) {
            document.querySelector(q).style.display = 'none'
        },

        chatEnter(e) {
            const message = e.target.value
            if (message.length > 0) {
                sendChatPost(message)
                e.target.value = ''
            }
        }
    },

    updated() {
        const el = document.querySelector('#line-input')
        if (el !== null) el.focus()
    }
})